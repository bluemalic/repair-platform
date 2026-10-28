package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.ai.agent.TestAgentModel;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 报修受理 / 智能分诊 Agent 的端到端验收（ADR-017）。
 *
 * <p>模型由 {@link TestAgentModel} 顶替（{@code @Primary} 的测试 Bean，不多起 Spring 上下文），
 * 所以整条链路——Controller → Service → 编排循环 → 工具 → 会话 → 出参——都能在 CI 上跑，
 * 却既不调模型也不花钱。
 *
 * <p>本类盯的**只有一件事**：Agent 的输出是"建议"，不是状态变更。所以每个用例都顺带断言
 * "工单数没变"，以及那些"模型可以乱说、但服务端必须纠正"的地方（编造的 id、漏填的字段）。
 */
@IntegrationTest
class TicketAgentTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;
    private static final long ROLE_ADMIN = 3L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private BuildingMapper buildingMapper;

    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestAgentModel agentModel;

    private String student;
    private String admin;
    private long studentId;
    private long buildingId;
    private long categoryId;

    /**
     * 楼栋与类别直接用种子数据（{@code R__seed.sql} 给租户 1 建了它们）：受理 Agent 的作用
     * 正是"从本校的类别表里挑一个"，所以测试也必须站在真实的基础数据上。
     */
    @BeforeEach
    void setUp() throws Exception {
        agentModel.reset();
        student = givenToken("test-agent-student", 2, ROLE_STUDENT);
        admin = givenToken("test-agent-admin", 3, ROLE_ADMIN);
        studentId = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, "test-agent-student")).getId();
        buildingId = buildingMapper.selectList(Wrappers.<Building>lambdaQuery()
                .eq(Building::getTenantId, 1L).eq(Building::getStatus, 1)).get(0).getId();
        categoryId = ticketCategoryMapper.selectList(Wrappers.<TicketCategory>lambdaQuery()
                .eq(TicketCategory::getTenantId, 1L).eq(TicketCategory::getStatus, 1)).get(0).getId();
    }

    @Test
    void 受理给出完整草稿_且一张工单都不落() throws Exception {
        long ticketsBefore = ticketCount();

        agentModel.willCallTool("listCategories", "{\"keyword\":\"水\"}");
        agentModel.willAnswer("""
                {"reply":"信息齐了，请确认后提交",
                 "draft":{"categoryId":%d,"urgency":2,"buildingId":%d,"room":"1-101","description":"水管漏水"},"missing":[]}
                """.formatted(categoryId, buildingId));

        JsonNode body = intake(student, Map.of("message", "1 号楼 1-101 水管漏水"));

        JsonNode data = body.path("data");
        assertThat(data.path("ready").asBoolean()).isTrue();
        assertThat(data.path("missing")).isEmpty();
        assertThat(data.path("sessionId").asText()).isNotBlank();
        assertThat(data.path("draft").path("categoryId").asLong()).isEqualTo(categoryId);
        // 展示用名字由服务端补（前端不必再查一次字典）
        assertThat(data.path("draft").path("categoryName").asText()).isNotBlank();
        assertThat(data.path("draft").path("buildingName").asText()).isNotBlank();
        // 轨迹里能看到它真的调了工具
        assertThat(data.path("trace")).isNotEmpty();
        assertThat(data.path("trace").get(0).path("tool").asText()).isEqualTo("listCategories");

        // ★ 这条是本类存在的理由：草稿只是建议，落单仍走提交报修接口
        assertThat(ticketCount()).isEqualTo(ticketsBefore);
    }

    @Test
    void 缺房间时_ready为假且missing含location() throws Exception {
        agentModel.willAnswer("""
                {"reply":"请问是哪个房间？",
                 "draft":{"categoryId":%d,"description":"水管漏水"},"missing":["room"]}
                """.formatted(categoryId));

        JsonNode data = intake(student, Map.of("message", "水管漏水")).path("data");

        assertThat(data.path("ready").asBoolean()).isFalse();
        // missing 由服务端算，不采信模型自报的那份：位置缺失一定是 location
        assertThat(data.path("missing").toString()).contains("location");
    }

    /**
     * 模型编造 id 不罕见（它可能按"常识"给一个 1）。放任它会走到"用户点了确认、提交时报错"
     * 那条更差的路径上，所以服务端逐个回库核对，不属于本租户的一律置空。
     */
    @Test
    void 模型编造的类别id被置空并计入缺失() throws Exception {
        agentModel.willAnswer("""
                {"reply":"请确认",
                 "draft":{"categoryId":999999999,"buildingId":%d,"room":"1-101","description":"灯不亮"},"missing":[]}
                """.formatted(buildingId));

        JsonNode data = intake(student, Map.of("message", "1 号楼 1-101 灯不亮")).path("data");

        assertThat(data.path("draft").path("categoryId").isNull() || data.path("draft").path("categoryId").isMissingNode())
                .isTrue();
        assertThat(data.path("ready").asBoolean()).isFalse();
        assertThat(data.path("missing").toString()).contains("categoryId");
    }

    /**
     * 模型把 id 写成**带引号的字符串**时也必须能落地（2026-10-16 的 Agent 评测踩到）。
     *
     * <p>触发条件很隐蔽，值得写清楚：工具返回给模型的字典是应用自己的 {@code ObjectMapper}
     * 序列化的，而它对 Long 一律走 {@code ToStringSerializer}——所以模型看到的是
     * {@code {"id":"2103798706715316226","name":"1号楼"}}。于是它对**长 id** 照抄成字符串，
     * 对短 id（{@code categoryId: 2}）才给数字。只认 {@code isNumber()} 的解析器会把长 id
     * **静默**丢掉（连告警都没有，因为丢在核对之前），现象是"位置怎么补都补不上、ready 永远是 false"，
     * 而模型在 reply 里还说"已记录：1号楼101，请确认"。
     */
    @Test
    void 模型把id写成字符串时也要接受() throws Exception {
        agentModel.willAnswer("""
                {"reply":"请确认",
                 "draft":{"categoryId":"%d","urgency":"2","buildingId":"%d","room":"1-101","description":"灯不亮"},"missing":[]}
                """.formatted(categoryId, buildingId));

        JsonNode data = intake(student, Map.of("message", "1 号楼 1-101 灯不亮")).path("data");

        assertThat(data.path("ready").asBoolean()).isTrue();
        assertThat(data.path("draft").path("categoryId").asLong()).isEqualTo(categoryId);
        assertThat(data.path("draft").path("buildingId").asLong()).isEqualTo(buildingId);
        assertThat(data.path("draft").path("urgency").asInt()).isEqualTo(2);
    }

    /** 没排脚本就说明这个用例没打算走 AI：假模型抛"模型不可用"，接口要如实转达。 */
    @Test
    void 模型不可用时返回40004() throws Exception {
        mockMvc.perform(post("/api/student/agent/intake")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("message", "水管漏水"))))
                .andExpect(jsonPath("$.code").value(40004));
    }

    @Test
    void 分诊给出建议_同样不写库() throws Exception {
        long ticketId = givenTicket("厕所堵了，水漫出来了");
        long ticketsBefore = ticketCount();

        agentModel.willCallTool("listCategories", "{}");
        agentModel.willAnswer("""
                {"suggestedCategoryId":%d,"suggestedUrgency":2,"reason":"描述里提到漏水外溢，属影响正常使用"}
                """.formatted(categoryId));

        JsonNode body = json(mockMvc.perform(post("/api/admin/tickets/" + ticketId + "/triage")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.code").value(0)).andReturn());

        JsonNode data = body.path("data");
        assertThat(data.path("suggestedCategoryId").asLong()).isEqualTo(categoryId);
        assertThat(data.path("suggestedCategoryName").asText()).isNotBlank();
        assertThat(data.path("suggestedUrgency").asInt()).isEqualTo(2);
        assertThat(data.path("reason").asText()).contains("漏水");
        assertThat(ticketCount()).isEqualTo(ticketsBefore);
    }

    /** 分诊属于派单动作，学生调不了（权限码 ticket:dispatch）。 */
    @Test
    void 学生不能调分诊接口() throws Exception {
        long ticketId = givenTicket("测试越权");

        mockMvc.perform(post("/api/admin/tickets/" + ticketId + "/triage")
                        .header("Authorization", "Bearer " + student))
                .andExpect(jsonPath("$.code").value(10003));
    }

    // ==================== 工具 ====================

    private int ticketCount() {
        return Math.toIntExact(ticketMapper.selectCount(null));
    }

    private JsonNode intake(String token, Map<String, Object> payload) throws Exception {
        return json(mockMvc.perform(post("/api/student/agent/intake")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(jsonPath("$.code").value(0)).andReturn());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 直接插一张待派单的工单（分诊的输入是"已存在的工单"，与受理那半条链无关）。 */
    private long givenTicket(String description) {
        Ticket ticket = new Ticket();
        ticket.setTenantId(1L);
        ticket.setTicketNo("WX-AGENT-" + System.nanoTime());
        ticket.setStudentId(studentId);
        ticket.setBuildingId(buildingId);
        ticket.setRoom("1-101");
        ticket.setCategoryId(categoryId);
        ticket.setUrgency(1);
        ticket.setStatus(20);
        ticket.setDescription(description);
        ticket.setSubmitTime(LocalDateTime.now());
        ticketMapper.insert(ticket);
        return ticket.getId();
    }

    private String givenToken(String username, int userType, long roleId) throws Exception {
        SysUser user = new SysUser();
        user.setTenantId(1L);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName(username);
        user.setUserType(userType);
        user.setStatus(1);
        sysUserMapper.insert(user);

        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(roleId);
        sysUserRoleMapper.insert(link);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "gdou", "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return json(result).path("data").path("tokenValue").asText();
    }
}
