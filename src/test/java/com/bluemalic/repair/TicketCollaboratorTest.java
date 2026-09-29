package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketCollaborator;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TicketCollaboratorMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 加协作者 / 移除协作者（多人同做一单，`docs/01` §4.5）。
 *
 * <p>这个功能的风险不在"能不能加"，而在**加了之后权限放开多大**，所以断言几乎都盯着边界：
 * <ul>
 *   <li><b>可见性</b>：协作者看得到这张单（包括不在他负责楼栋里的），移除后立刻看不到——
 *       可见范围与操作权必须一致，这是"跨楼栋强制派单"踩过的同一个坑</li>
 *   <li><b>能做什么</b>：能到场、能完工；**不能接单、不能驳回**（那是处置权，归主责与调度）</li>
 *   <li><b>谁完成算数</b>：先完成者算数，第二个人被状态机挡下（需求文档当初卡住的就是这一条）</li>
 *   <li><b>主责不变</b>：`worker_id` 始终是主责——统计归因、超时节点都对着它，协作者不参与归因</li>
 * </ul>
 */
@IntegrationTest
class TicketCollaboratorTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;
    private static final long ROLE_WORKER = 2L;
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
    private WorkerBuildingMapper workerBuildingMapper;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private TicketLogMapper ticketLogMapper;

    @Autowired
    private TicketCollaboratorMapper ticketCollaboratorMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** 本用例建过的工单：结束前把这些 id 从 Redis 的超时 ZSet 里摘掉（见 {@link #cleanupTimeoutMembers()}）。 */
    private final List<String> createdTickets = new ArrayList<>();

    /**
     * 把本用例登记的工单从超时 ZSet 里摘掉。数据库那边由 {@code @Transactional} 回滚，
     * 但 **Redis 不会回滚**——留下的成员到期后会指向一张不存在的工单，
     * 让后来跑超时用例的人收到一句莫名其妙的"工单不存在"（这是仓库里已有的一个坑，
     * 新用例至少不该往里再添垃圾）。
     */
    @AfterEach
    void cleanupTimeoutMembers() {
        if (createdTickets.isEmpty()) {
            return;
        }
        Object[] ids = createdTickets.toArray();
        for (String node : List.of("ACCEPT", "PROCESS", "EVAL")) {
            stringRedisTemplate.opsForZSet().remove("ticket:timeout:" + node, ids);
        }
    }

    // ==================== 核心：协作者能干活，且看得到 ====================

    @Test
    void collaboratorSeesTheTicketAndCanArriveAndFinish() throws Exception {
        String admin = givenToken("test-co-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-co-student", 1, ROLE_STUDENT, null);
        // 主责负责 1 号楼；协作者**不负责任何楼栋**——这样"看得到"只可能来自协作关系
        String owner = givenToken("test-co-owner", 2, ROLE_WORKER, List.of(1L));
        String helper = givenToken("test-co-helper", 2, ROLE_WORKER, null);
        String ticketId = dispatchedTicket(student, admin, "test-co-owner");

        // 加协作之前：不负责这栋楼的师傅看不到（这就是"派了却看不到"的坑）
        assertThat(listIds(helper, "/api/worker/tickets?scope=mine")).doesNotContain(ticketId);

        postJson(admin, "/api/admin/tickets/" + ticketId + "/collaborators",
                Map.of("workerId", userIdOf("test-co-helper")))
                .andExpect(jsonPath("$.code").value(0));

        // 看得到了，而且带「协作」标记（前端按它区分"派给我的"与"我协作的"）
        JsonNode row = rowOf(helper, "/api/worker/tickets?scope=mine", ticketId);
        assertThat(row.path("collaborative").asBoolean()).isTrue();
        assertThat(row.path("workerId").asLong()).isEqualTo(userIdOf("test-co-owner"));

        // 主责接单后，协作者能扫码到场、能完工——与主责走的是同一套校验
        postJson(owner, "/api/worker/tickets/" + ticketId + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        postJson(helper, "/api/worker/tickets/" + ticketId + "/arrive", Map.of("repairCode", "482913"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(ticketOf(ticketId).getArriveTime()).isNotNull();
        postJson(helper, "/api/worker/tickets/" + ticketId + "/finish",
                Map.of("resultDesc", "两人一起修好了", "resultImages", List.of()))
                .andExpect(jsonPath("$.code").value(0));

        // 状态到了待验收，但 **worker_id 仍是主责**：统计归因、超时节点都对着他（docs/01 §4.5）
        Ticket after = ticketOf(ticketId);
        assertThat(after.getStatus()).isEqualTo(40);
        assertThat(after.getWorkerId()).isEqualTo(userIdOf("test-co-owner"));
        // "是谁完成的"记在日志里，不新增字段
        List<TicketLog> logs = logsOf(ticketId);
        TicketLog finish = logs.get(logs.size() - 1);
        assertThat(finish.getAction()).isEqualTo("FINISH");
        assertThat(finish.getOperatorId()).isEqualTo(userIdOf("test-co-helper"));

        // 通知：学生收到与主责完工一模一样的通知；主责另收一条"已由协作者完工"
        assertThat(notificationTitles(student)).contains("维修完成待验收");
        assertThat(notificationTitles(owner)).contains("工单已由协作者完工");
    }

    @Test
    void firstFinisherWinsAndTheSecondIsRejectedByStateMachine() throws Exception {
        String admin = givenToken("test-co2-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-co2-student", 1, ROLE_STUDENT, null);
        String owner = givenToken("test-co2-owner", 2, ROLE_WORKER, List.of(1L));
        String helper = givenToken("test-co2-helper", 2, ROLE_WORKER, null);
        String ticketId = dispatchedTicket(student, admin, "test-co2-owner");
        addCollaborator(admin, ticketId, "test-co2-helper");

        postJson(owner, "/api/worker/tickets/" + ticketId + "/accept", Map.of());
        postJson(owner, "/api/worker/tickets/" + ticketId + "/arrive", Map.of("repairCode", "482913"));
        postJson(helper, "/api/worker/tickets/" + ticketId + "/finish",
                Map.of("resultDesc", "协作的这位先完工了", "resultImages", List.of()))
                .andExpect(jsonPath("$.code").value(0));

        // 主责再点完工：40 待验收不允许再完工——**这就是"重复完工怎么办"的答案**，
        // 不靠两个人约定，靠条件更新 + 状态机
        postJson(owner, "/api/worker/tickets/" + ticketId + "/finish",
                Map.of("resultDesc", "我再点一次", "resultImages", List.of()))
                .andExpect(jsonPath("$.code").value(20002));
        // 只留一条 FINISH，结果描述保持第一个人的
        assertThat(logsOf(ticketId)).extracting(TicketLog::getAction)
                .containsExactly("SUBMIT", "DISPATCH", "ADD_COLLABORATOR", "ACCEPT", "ARRIVE", "FINISH");
        assertThat(ticketOf(ticketId).getResultDesc()).isEqualTo("协作的这位先完工了");
    }

    // ==================== 边界：协作者不能做什么 ====================

    @Test
    void collaboratorCannotAcceptOrReject() throws Exception {
        String admin = givenToken("test-co3-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-co3-student", 1, ROLE_STUDENT, null);
        String owner = givenToken("test-co3-owner", 2, ROLE_WORKER, List.of(1L));
        String helper = givenToken("test-co3-helper", 2, ROLE_WORKER, null);
        String ticketId = dispatchedTicket(student, admin, "test-co3-owner");
        addCollaborator(admin, ticketId, "test-co3-helper");

        // 接单是"我认领这单"——协作者是被拉来的，不替他接单
        postJson(helper, "/api/worker/tickets/" + ticketId + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(20003));
        // 驳回是"这单不该我做"——那是对**派单**的判断，属于主责与调度
        postJson(helper, "/api/worker/tickets/" + ticketId + "/reject",
                Map.of("reason", "我不该做这单"))
                .andExpect(jsonPath("$.code").value(20003));
        assertThat(ticketOf(ticketId).getStatus()).isEqualTo(20);

        // 主责仍然能做这两件事（没有把既有路径改坏）
        postJson(owner, "/api/worker/tickets/" + ticketId + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        postJson(owner, "/api/worker/tickets/" + ticketId + "/reject", Map.of("reason", "临时有事"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(ticketOf(ticketId).getStatus()).isEqualTo(80);
    }

    @Test
    void addCollaboratorRejectsBadTargetsAndStatuses() throws Exception {
        String admin = givenToken("test-co4-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-co4-student", 1, ROLE_STUDENT, null);
        String owner = givenToken("test-co4-owner", 2, ROLE_WORKER, List.of(1L));
        String other = givenToken("test-co4-other", 2, ROLE_WORKER, null);
        String ticketId = dispatchedTicket(student, admin, "test-co4-owner");

        // 主责本人不用加成协作者
        postJson(admin, "/api/admin/tickets/" + ticketId + "/collaborators",
                Map.of("workerId", userIdOf("test-co4-owner")))
                .andExpect(jsonPath("$.code").value(10001));
        // 学生不是维修工（与派单同一句文案口径）
        postJson(admin, "/api/admin/tickets/" + ticketId + "/collaborators",
                Map.of("workerId", userIdOf("test-co4-student")))
                .andExpect(jsonPath("$.code").value(10001));
        // 重复加
        addCollaborator(admin, ticketId, "test-co4-other");
        postJson(admin, "/api/admin/tickets/" + ticketId + "/collaborators",
                Map.of("workerId", userIdOf("test-co4-other")))
                .andExpect(jsonPath("$.code").value(10001));
        // 上限 3 人
        String third = givenToken("test-co4-third", 2, ROLE_WORKER, null);
        String fourth = givenToken("test-co4-fourth", 2, ROLE_WORKER, null);
        String fifth = givenToken("test-co4-fifth", 2, ROLE_WORKER, null);
        addCollaborator(admin, ticketId, "test-co4-third");
        addCollaborator(admin, ticketId, "test-co4-fourth");
        postJson(admin, "/api/admin/tickets/" + ticketId + "/collaborators",
                Map.of("workerId", userIdOf("test-co4-fifth")))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("拆单")));

        // 完工之后（40）不该再拉人进来：活已经干完了
        postJson(owner, "/api/worker/tickets/" + ticketId + "/accept", Map.of());
        postJson(owner, "/api/worker/tickets/" + ticketId + "/arrive", Map.of("repairCode", "482913"));
        postJson(owner, "/api/worker/tickets/" + ticketId + "/finish",
                Map.of("resultDesc", "修好了", "resultImages", List.of()))
                .andExpect(jsonPath("$.code").value(0));
        postJson(admin, "/api/admin/tickets/" + ticketId + "/collaborators",
                Map.of("workerId", userIdOf("test-co4-fifth")))
                .andExpect(jsonPath("$.code").value(20002));
    }

    @Test
    void addAndRemoveCollaboratorRequireDispatchPermission() throws Exception {
        String admin = givenToken("test-co5-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-co5-student", 1, ROLE_STUDENT, null);
        String owner = givenToken("test-co5-owner", 2, ROLE_WORKER, List.of(1L));
        String helper = givenToken("test-co5-helper", 2, ROLE_WORKER, null);
        String ticketId = dispatchedTicket(student, admin, "test-co5-owner");

        Map<String, Object> body = Map.of("workerId", userIdOf("test-co5-helper"));
        mockMvc.perform(post("/api/admin/tickets/" + ticketId + "/collaborators")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));
        mockMvc.perform(post("/api/admin/tickets/" + ticketId + "/collaborators")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));
    }

    @Test
    void removingCollaboratorTakesAwayVisibilityImmediately() throws Exception {
        String admin = givenToken("test-co6-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-co6-student", 1, ROLE_STUDENT, null);
        String owner = givenToken("test-co6-owner", 2, ROLE_WORKER, List.of(1L));
        String helper = givenToken("test-co6-helper", 2, ROLE_WORKER, null);
        String ticketId = dispatchedTicket(student, admin, "test-co6-owner");
        addCollaborator(admin, ticketId, "test-co6-helper");
        assertThat(listIds(helper, "/api/worker/tickets?scope=mine")).contains(ticketId);

        mockMvc.perform(delete("/api/admin/tickets/" + ticketId + "/collaborators/" + userIdOf("test-co6-helper"))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.code").value(0));

        // 看不到、也干不了（可见范围与操作权一起收回去）
        assertThat(listIds(helper, "/api/worker/tickets?scope=mine")).doesNotContain(ticketId);
        mockMvc.perform(get("/api/worker/tickets/" + ticketId)
                        .header("Authorization", "Bearer " + helper))
                .andExpect(jsonPath("$.code").value(20001));
        // 留痕 + 通知当事人（他刚失去这张单，不告诉他他会照旧去现场）
        assertThat(logsOf(ticketId)).extracting(TicketLog::getAction)
                .contains("ADD_COLLABORATOR", "REMOVE_COLLABORATOR");
        assertThat(notificationTitles(helper)).contains("你已不是协作人");
        assertThat(ticketCollaboratorMapper.selectCount(Wrappers.<TicketCollaborator>lambdaQuery()
                .eq(TicketCollaborator::getTicketId, Long.parseLong(ticketId)))).isZero();

        // 移除不存在的关系：明确报错，不静默成功
        mockMvc.perform(delete("/api/admin/tickets/" + ticketId + "/collaborators/" + userIdOf("test-co6-helper"))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void detailShowsCollaboratorsForBothAdminAndWorker() throws Exception {
        String admin = givenToken("test-co7-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-co7-student", 1, ROLE_STUDENT, null);
        String owner = givenToken("test-co7-owner", 2, ROLE_WORKER, List.of(1L));
        String helper = givenToken("test-co7-helper", 2, ROLE_WORKER, null);
        String ticketId = dispatchedTicket(student, admin, "test-co7-owner");
        addCollaborator(admin, ticketId, "test-co7-helper");

        // 管理端与师傅端的详情都要能看到"谁在一起干"（两端读同一个 VO）
        for (String token : List.of(admin, owner, helper)) {
            String url = token.equals(admin) ? "/api/admin/tickets/" + ticketId : "/api/worker/tickets/" + ticketId;
            JsonNode detail = getJson(token, url).path("data");
            assertThat(detail.path("collaborators")).hasSize(1);
            assertThat(detail.path("collaborators").get(0).path("workerName").asText())
                    .isEqualTo("test-co7-helper");
        }
    }

    // ==================== 工具 ====================

    /** 提交 + 派给指定师傅（停在 20 待接单），并登记进本用例的清理名单。 */
    private String dispatchedTicket(String student, String admin, String workerUsername) throws Exception {
        MvcResult result = postJson(student, "/api/student/tickets", Map.of(
                        "repairCode", "482913", "categoryId", 1, "description", "【测试】协作测试用单"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        String ticketId = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asText();
        createdTickets.add(ticketId);
        postJson(admin, "/api/admin/tickets/" + ticketId + "/dispatch",
                Map.of("workerId", userIdOf(workerUsername))).andExpect(jsonPath("$.code").value(0));
        return ticketId;
    }

    private void addCollaborator(String admin, String ticketId, String workerUsername) throws Exception {
        postJson(admin, "/api/admin/tickets/" + ticketId + "/collaborators",
                Map.of("workerId", userIdOf(workerUsername)))
                .andExpect(jsonPath("$.code").value(0));
    }

    private Ticket ticketOf(String ticketId) {
        return ticketMapper.selectById(Long.parseLong(ticketId));
    }

    private List<TicketLog> logsOf(String ticketId) {
        return ticketLogMapper.selectList(Wrappers.<TicketLog>lambdaQuery()
                .eq(TicketLog::getTicketId, Long.parseLong(ticketId))
                .orderByAsc(TicketLog::getCreateTime));
    }

    private JsonNode rowOf(String token, String url, String ticketId) throws Exception {
        for (JsonNode node : getJson(token, url).path("data").path("list")) {
            if (ticketId.equals(node.path("id").asText())) {
                return node;
            }
        }
        throw new AssertionError("列表里没有工单 " + ticketId + "：" + url);
    }

    private List<String> notificationTitles(String token) throws Exception {
        return java.util.stream.StreamSupport
                .stream(getJson(token, "/api/notifications?pageNum=1&pageSize=10").path("data").path("list")
                        .spliterator(), false)
                .map(node -> node.path("title").asText()).toList();
    }

    private List<String> listIds(String token, String url) throws Exception {
        return java.util.stream.StreamSupport
                .stream(getJson(token, url).path("data").path("list").spliterator(), false)
                .map(node -> node.path("id").asText()).toList();
    }

    private String givenToken(String username, int userType, long roleId, List<Long> buildingIds) throws Exception {
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

        if (buildingIds != null) {
            for (Long buildingId : buildingIds) {
                WorkerBuilding wb = new WorkerBuilding();
                wb.setTenantId(1L);
                wb.setWorkerId(user.getId());
                wb.setBuildingId(buildingId);
                workerBuildingMapper.insert(wb);
            }
        }

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "gdou", "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("tokenValue").asText();
    }

    private long userIdOf(String username) {
        return sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username)).getId();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String token, String url,
                                                                       Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode getJson(String token, String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
