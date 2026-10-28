package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.ai.knowledge.TestAdviceModel;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.KnowledgeEntry;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.KnowledgeEntryMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
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
 * 师傅端「AI 排查建议」的端到端验收（阶段 2 · ADR-018）。
 *
 * <p>模型由 {@link TestAdviceModel} 顶替，所以整条链路——Controller → 检索（**真跑 ngram 全文索引**）
 * → 生成（假模型）→ 引用核对 → 出参——都能在 CI 上跑，却既不调模型也不花钱。
 *
 * <p>本类盯三件事：
 * <ol>
 *   <li>**引用核对**：模型给的 id 不在本次检索结果里就得被剔除，剔除后没剩下有效引用的步骤要标出来</li>
 *   <li>**零命中不调模型**：知识库里没有相关条目时如实说没有，且**一次模型调用都不发**（省钱是设计的一部分）</li>
 *   <li>不写库 + 权限：建议不改数据；看不到这张单的人拿不到建议</li>
 * </ol>
 *
 * <p>检索用的是 {@code R__seed.sql} 里那 7 条演示条目（租户 1）——所以这里测到的是**真实的全文索引行为**，
 * 不是 Mock 出来的"假设它能匹配"。
 */
@IntegrationTest
class KnowledgeAdviceTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;
    private static final long ROLE_WORKER = 2L;
    private static final long TENANT = 1L;

    /** 种子条目：水管接头漏水（类别=水电），描述与演示工单同字 */
    private static final long SEED_WATER_ENTRY = 9001L;

    /** 一段能命中 {@link #SEED_WATER_ENTRY} 的描述（2-gram 与它高度重叠，且不与其他条目重叠） */
    private static final String WATER_DESC = "卫生间水管接头一直滴水";

    /** 一段命不中任何条目的描述 */
    private static final String NOTHING_DESC = "顶楼的旗杆歪了，需要人去看看";

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
    private KnowledgeEntryMapper knowledgeEntryMapper;

    @Autowired
    private WorkerBuildingMapper workerBuildingMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestAdviceModel adviceModel;

    private String worker;
    private String student;
    private long studentId;
    private long buildingId;
    private long waterCategoryId;

    @BeforeEach
    void setUp() throws Exception {
        adviceModel.reset();
        worker = givenToken("test-advice-worker", 2, ROLE_WORKER);
        student = givenToken("test-advice-student", 1, ROLE_STUDENT);
        studentId = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, "test-advice-student")).getId();
        buildingId = buildingMapper.selectList(Wrappers.<Building>lambdaQuery()
                .eq(Building::getTenantId, TENANT).eq(Building::getStatus, 1)).get(0).getId();
        // 师傅的可见范围是「我负责楼栋 OR 派给我的 OR 我协作的」三层叠加（docs/01 §4.2）：
        // 不给他挂一栋楼，他连自己租户里这张单都看不到（接口会如实回 20001）——这是权限在干活，不是 bug
        long workerId = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, "test-advice-worker")).getId();
        WorkerBuilding responsibility = new WorkerBuilding();
        responsibility.setTenantId(TENANT);
        responsibility.setWorkerId(workerId);
        responsibility.setBuildingId(buildingId);
        workerBuildingMapper.insert(responsibility);
        waterCategoryId = ticketCategoryMapper.selectOne(Wrappers.<TicketCategory>lambdaQuery()
                .eq(TicketCategory::getTenantId, TENANT).eq(TicketCategory::getName, "水电")).getId();
    }

    @Test
    void 命中知识库时给出带引用的步骤_且编造的引用被剔除() throws Exception {
        long ticketId = givenTicket(WATER_DESC);
        String statusBefore = ticketStatusOf(ticketId);

        adviceModel.willAnswer("""
                ```json
                {"suggestion":"先关角阀再看接头",
                 "steps":[{"text":"先关掉这一路的角阀","citations":[%d]},
                          {"text":"拧紧接头螺纹，清干净旧生料带","citations":[999999,%d]},
                          {"text":"顺便检查一下别的地方","citations":[888888]}]}
                ```
                """.formatted(SEED_WATER_ENTRY, SEED_WATER_ENTRY));

        JsonNode data = advice(worker, ticketId);

        assertThat(data.path("generated").asBoolean()).isTrue();
        assertThat(data.path("retriever").asText()).isEqualTo("keyword");
        assertThat(data.path("suggestion").asText()).isNotBlank();

        // 命中里确实有那条种子条目（真跑了 ngram 索引）
        assertThat(data.path("sources")).isNotEmpty();
        assertThat(data.path("sources").get(0).path("id").asLong()).isEqualTo(SEED_WATER_ENTRY);

        JsonNode steps = data.path("steps");
        assertThat(steps).hasSize(3);
        // 第 1 条：合法引用，保留
        assertThat(steps.get(0).path("citations").get(0).asLong()).isEqualTo(SEED_WATER_ENTRY);
        assertThat(steps.get(0).path("cited").asBoolean()).isTrue();
        // 第 2 条：混了一个编造的 id，剔掉之后还剩合法的那条
        assertThat(steps.get(1).path("citations")).hasSize(1);
        assertThat(steps.get(1).path("citations").get(0).asLong()).isEqualTo(SEED_WATER_ENTRY);
        // 第 3 条：引用全是编的 → 引用清空、标成"没有知识库依据"（而不是悄悄删掉这一步）
        assertThat(steps.get(2).path("citations")).isEmpty();
        assertThat(steps.get(2).path("cited").asBoolean()).isFalse();

        // ★ 建议不改数据：状态与更新时间都不动（与两个 Agent 同一条不变量）
        assertThat(ticketStatusOf(ticketId)).isEqualTo(statusBefore);
    }

    /** 知识库里没有相关条目时：如实说没有，而且**一次模型调用都不发**（ADR-018 明确要省的钱）。 */
    @Test
    void 零命中时不调模型并如实说没有() throws Exception {
        long ticketId = givenTicket(NOTHING_DESC);

        JsonNode data = advice(worker, ticketId);

        assertThat(data.path("generated").asBoolean()).isFalse();
        assertThat(data.path("steps")).isEmpty();
        assertThat(data.path("sources")).isEmpty();
        assertThat(data.path("suggestion").asText()).contains("知识库里还没有");
        assertThat(adviceModel.callCount()).as("零命中不该调模型").isZero();
    }

    /** 停用的条目不进检索结果——这是"下线一条过期知识"的唯一手段（没有删除）。 */
    @Test
    void 停用的条目不会被检索到() throws Exception {
        KnowledgeEntry entry = knowledgeEntryMapper.selectById(SEED_WATER_ENTRY);
        assertThat(entry).as("种子条目应在（R__seed 建的）").isNotNull();
        entry.setStatus(0);
        knowledgeEntryMapper.updateById(entry);

        long ticketId = givenTicket(WATER_DESC);
        JsonNode data = advice(worker, ticketId);

        assertThat(data.path("sources")).isEmpty();
        assertThat(data.path("generated").asBoolean()).isFalse();
        assertThat(adviceModel.callCount()).isZero();
    }

    /** 学生没有 ticket:list:assigned：排查建议是维修工的工具，学生连接口都进不去。 */
    @Test
    void 学生不能要排查建议() throws Exception {
        long ticketId = givenTicket(WATER_DESC);

        mockMvc.perform(post("/api/worker/tickets/" + ticketId + "/advice")
                        .header("Authorization", "Bearer " + student))
                .andExpect(jsonPath("$.code").value(10003));
    }

    // ==================== 工具 ====================

    /** 返回的是响应里的 {@code data} 节点（不是根节点——**这个区别踩过一次**：拿根节点断言时，
     * 缺失字段会被当成 false / 空串"静默通过"）。 */
    private JsonNode advice(String token, long ticketId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/worker/tickets/" + ticketId + "/advice")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return json(result).path("data");
    }

    private long givenTicket(String description) {
        Ticket ticket = new Ticket();
        ticket.setTenantId(TENANT);
        ticket.setTicketNo("WX-ADVICE-" + System.nanoTime());
        ticket.setStudentId(studentId);
        ticket.setBuildingId(buildingId);
        ticket.setRoom("1-101");
        ticket.setCategoryId(waterCategoryId);
        ticket.setUrgency(1);
        ticket.setStatus(20);
        ticket.setDescription(description);
        ticket.setSubmitTime(LocalDateTime.now());
        ticketMapper.insert(ticket);
        return ticket.getId();
    }

    /** 工单的"没被动过"指纹：状态 + 更新时间（写库了这两个至少有一个会变）。 */
    private String ticketStatusOf(long ticketId) {
        Ticket ticket = ticketMapper.selectById(ticketId);
        return ticket.getStatus() + "@" + ticket.getUpdateTime();
    }

    private String givenToken(String username, int userType, long roleId) throws Exception {
        SysUser user = new SysUser();
        user.setTenantId(TENANT);
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

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

}
