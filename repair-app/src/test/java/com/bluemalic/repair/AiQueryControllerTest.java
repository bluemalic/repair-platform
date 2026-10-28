package com.bluemalic.repair;

import com.bluemalic.repair.ai.AiQueryResult;
import com.bluemalic.repair.ai.AiReadOnlyAccountBootstrap;
import com.bluemalic.repair.ai.QueryPlan;
import com.bluemalic.repair.ai.TestAiAssistant;
import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.config.AiProperties;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 问数接口（{@code POST /api/ai/query}）。
 *
 * <p>这个用例盯的是**接口层**那几件事：路由与权限、入参校验、限流档位、以及**响应的 JSON 形状**
 * ——尤其"数字必须是数字"这一条：本项目的全局规则会把 {@code Long} 序列化成字符串，
 * 而问数结果里的计数要给 ECharts 用，变成 {@code "5"} 图就画不出来。这条只有在 HTTP 这一层
 * （真序列化）才验得到，服务层的单测验不到。
 *
 * <p>模型由 {@link TestAiAssistant} 顶替（{@code @Primary} 的测试 Bean，不多起上下文），
 * 所以整条链路——接口 → 编排 → 安全网关 → 只读账号 → 数据库——真的跑了一遍，而不调模型。
 */
@IntegrationTest
class AiQueryControllerTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_ADMIN = 3L;
    private static final long ROLE_STUDENT = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestAiAssistant assistant;

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void createReadOnlyAccount() throws SQLException {
        // 只读账号是 DDL 建的，不参与事务回滚；在测试里按需建、用完删（与 AiQueryExecutorTest 同）
        new AiReadOnlyAccountBootstrap(dataSource, aiProperties)
                .ensureAccount(aiProperties.getDbUsername(), aiProperties.getDbPassword());
    }

    @AfterEach
    void dropReadOnlyAccount() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP USER IF EXISTS '" + aiProperties.getDbUsername() + "'@'%'");
        }
    }

    // ==================== 正常路径：一次完整的问数 ====================

    @Test
    void answersQuestionWithDataChartAndConclusion() throws Exception {
        String admin = givenToken("test-ai-admin", UserType.ADMIN.getCode(), ROLE_ADMIN);
        assistant.willReturn(new QueryPlan("SELECT COUNT(*) AS 楼栋数 FROM building", "none", null, "楼栋数"));
        assistant.willConclude("目前共有若干栋楼。");

        MvcResult result = postJson(admin, "/api/ai/query", Map.of("question", "一共有几栋楼"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.question").value("一共有几栋楼"))
                .andExpect(jsonPath("$.data.columns[0]").value("楼栋数"))
                .andExpect(jsonPath("$.data.chart.type").value("none"))
                .andExpect(jsonPath("$.data.conclusion").value("目前共有若干栋楼。"))
                .andExpect(jsonPath("$.data.rowLimited").value(false))
                .andReturn();

        JsonNode data = readJson(result).path("data");
        // ① 数字必须是**数字**：全局的 Long→字符串规则只该管 ID，不该管统计值
        assertThat(data.path("rows").get(0).get(0).isNumber()).as("计数应是数字而不是字符串").isTrue();
        // ② 返回的 SQL 是注入过租户条件的那条，不是模型原话（用户看到的与实际执行的一致）
        assertThat(data.path("sql").asText()).contains("building.tenant_id = 1").contains("deleted = 0");
        // ③ 耗时按数字输出（同样是计数字段，见 PageResult 的处理）
        assertThat(data.path("elapsedMs").isNumber()).isTrue();
        // ④ 结论确实基于真实查询结果
        AiQueryResult seen = assistant.summarizedRows();
        assertThat(seen).isNotNull();
        assertThat(seen.columns()).containsExactly("楼栋数");
    }

    // ==================== 权限与入参 ====================

    @Test
    void studentCannotAskAi() throws Exception {
        String student = givenToken("test-ai-student", UserType.STUDENT.getCode(), ROLE_STUDENT);

        // ai:query 只授给后勤管理：问数能看到全租户的汇总数据，不是学生该有的能力
        postJson(student, "/api/ai/query", Map.of("question", "全校有多少工单"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));
    }

    @Test
    void rejectsBlankQuestion() throws Exception {
        String admin = givenToken("test-ai-blank", UserType.ADMIN.getCode(), ROLE_ADMIN);

        postJson(admin, "/api/ai/query", Map.of("question", "   "))
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void rejectsOverlongQuestion() throws Exception {
        String admin = givenToken("test-ai-long", UserType.ADMIN.getCode(), ROLE_ADMIN);

        // 长度上限是成本控制：这句话会进提示词，长度直接变成 token 账单
        postJson(admin, "/api/ai/query", Map.of("question", "问".repeat(201)))
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void reportsGatewayRejection() throws Exception {
        String admin = givenToken("test-ai-reject", UserType.ADMIN.getCode(), ROLE_ADMIN);
        // 模型（或提示注入）让它删数据：网关必须拦下，接口不能执行
        assistant.willReturn(new QueryPlan("DELETE FROM ticket", "none", null, null));

        postJson(admin, "/api/ai/query", Map.of("question", "把工单都删了"))
                .andExpect(jsonPath("$.code").value(40002));
    }

    // ==================== 限流：问数用更严的独立档位 ====================

    @Test
    void usesAiSpecificRateLimitTier() throws Exception {
        String admin = givenToken("test-ai-ratelimit", UserType.ADMIN.getCode(), ROLE_ADMIN);
        assistant.willReturn(new QueryPlan("SELECT COUNT(*) AS 楼栋数 FROM building", "none", null, "楼栋数"));

        int allowed = aiProperties.getRateLimitMaxRequests();
        for (int i = 0; i < allowed; i++) {
            postJson(admin, "/api/ai/query", Map.of("question", "第 " + i + " 次"))
                    .andExpect(jsonPath("$.code").value(0));
        }

        // 第 allowed + 1 次触到这一档的阈值（默认档是 60，问数这一档默认 10）
        postJson(admin, "/api/ai/query", Map.of("question", "再来一次"))
                .andExpect(jsonPath("$.code").value(10004));

        // 流式与同步是**同一笔开销**：计数按档位共用，所以额度用光之后流式也进不去。
        // 这条断言盯的是"别把成本上限拆成两份"——按接口计数时两个接口各有各的额度，等于翻倍。
        mockMvc.perform(get("/api/ai/query/stream")
                        .param("question", "再流一次")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.code").value(10004));
    }

    // ==================== 流式：SSE 帧（GET /api/ai/query/stream） ====================

    @Test
    void streamsStagesInOrderThenDone() throws Exception {
        String admin = givenToken("test-ai-sse", UserType.ADMIN.getCode(), ROLE_ADMIN);
        assistant.willReturn(new QueryPlan("SELECT name AS building FROM building", "bar", "building", null));
        assistant.willConclude("若干栋楼。");

        List<SseFrame> frames = stream(admin, "有哪些楼");

        // 帧的顺序就是前端的渲染顺序：SQL → 数据 → 收尾（结论 + 耗时）
        assertThat(frames).extracting(SseFrame::name).containsExactly("sql", "data", "done");

        JsonNode sql = frames.get(0).data();
        // 推给前端的就是**实际执行**的那条 SQL（注入过租户条件），不是模型的原话
        assertThat(sql.path("sql").asText()).contains("building.tenant_id = 1");
        assertThat(sql.path("chart").path("type").asText()).isEqualTo("bar");
        assertThat(sql.path("chart").path("x").asText()).isEqualTo("building");

        JsonNode data = frames.get(1).data();
        assertThat(data.path("columns").get(0).asText()).isEqualTo("building");
        assertThat(data.path("rowLimited").isBoolean()).isTrue();
        assertThat(data.path("rows").get(0).get(0).isTextual()).isTrue();  // 楼栋名是文本

        JsonNode done = frames.get(2).data();
        assertThat(done.path("conclusion").asText()).isEqualTo("若干栋楼。");
        // 数字必须是数字：全局的 Long→字符串规则只该管 ID，不该管耗时（前端要拿它算/显示）
        assertThat(done.path("elapsedMs").isNumber()).isTrue();
    }

    @Test
    void streamsErrorFrameInsteadOfJsonWhenTheQueryFails() throws Exception {
        String admin = givenToken("test-ai-sse-error", UserType.ADMIN.getCode(), ROLE_ADMIN);
        // 模型（或提示注入）让它删数据：这是"工作开始之后"的失败，走 error 帧
        assistant.willReturn(new QueryPlan("DELETE FROM ticket", "none", null, null));

        List<SseFrame> frames = stream(admin, "把工单都删了");

        assertThat(frames).extracting(SseFrame::name).containsExactly("error");
        assertThat(frames.get(0).data().path("code").asInt()).isEqualTo(40002);
        // 帧里带 traceId：用户截图报错时，凭它就能在后端日志里找到这一次问数
        assertThat(frames.get(0).data().path("traceId").asText()).isNotBlank();
    }

    @Test
    void streamFailsBeforeStartingWhenTheQuestionIsInvalid() throws Exception {
        String admin = givenToken("test-ai-sse-blank", UserType.ADMIN.getCode(), ROLE_ADMIN);

        // 参数不合法属于"还没开始工作"：仍然是普通 JSON 错误（与 POST 同一份规则、同一个码），
        // 不是 error 帧。两条错误通道的分界在这里被钉住——DEFAULT 是 10001 而不是 406/500。
        mockMvc.perform(get("/api/ai/query/stream")
                        .param("question", "   ")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void studentCannotStreamAi() throws Exception {
        String student = givenToken("test-ai-sse-student", UserType.STUDENT.getCode(), ROLE_STUDENT);

        mockMvc.perform(get("/api/ai/query/stream")
                        .param("question", "全校有多少工单")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));
    }

    // ==================== 工具 ====================

    /**
     * 跑一次流式问数并解析出所有帧。
     *
     * <p>两处要留意的：
     * <ul>
     *   <li><b>必须等异步跑完</b>（{@code getAsyncResult}）：工作在 ai-stream 线程池里，
     *       不等就直接 dispatch 会读到半截报文——在快机器上偶尔绿、慢机器上红。</li>
     *   <li>断言只看"事件名 + JSON 载荷"，**不锁框架拼出来的字节格式**（{@code event:x\ndata:...} 的
     *       换行细节是 Spring 的事）。</li>
     * </ul>
     */
    private List<SseFrame> stream(String token, String question) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/ai/query/stream")
                        .param("question", question)
                        .header("Authorization", "Bearer " + token))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(30_000);
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<SseFrame> frames = new ArrayList<>();
        for (String block : body.split("\\r?\\n\\r?\\n")) {
            String name = null;
            String data = null;
            for (String line : block.split("\\r?\\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data = line.substring("data:".length()).trim();
                }
            }
            if (name != null && data != null) {
                frames.add(new SseFrame(name, objectMapper.readTree(data)));
            }
        }
        assertThat(frames).as("一个帧都没解析出来，原始报文：%s", body).isNotEmpty();
        return frames;
    }

    /** 一个 SSE 帧：事件名 + JSON 载荷。 */
    private record SseFrame(String name, JsonNode data) {
    }

    /** 造账号 + 角色并登录拿 token（租户 1 = 种子数据 gdou）。每个用例用不同用户名，避开限流计数互相影响。 */
    private String givenToken(String username, int userType, long roleId) throws Exception {
        SysUser user = new SysUser();
        user.setTenantId(1L);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName(username);
        user.setUserType(userType);
        user.setStatus(1);
        user.setMustChangePassword(0);
        sysUserMapper.insert(user);

        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(roleId);
        sysUserRoleMapper.insert(link);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "gdou", "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("tokenValue").asText();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String token, String url, Map<String, ?> body)
            throws Exception {
        return mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
