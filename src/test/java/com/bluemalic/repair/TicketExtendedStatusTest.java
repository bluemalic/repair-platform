package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.entity.Notification;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.NotificationMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 扩展状态：35 待料 / 45 无法维修 / 75 已合并（docs/01 §4.7）。
 *
 * <p>这些状态的价值不在"多几个枚举"，而在**每条边界的处置权**：谁能标记、谁能恢复、
 * 谁能合并、进入 45/75 后超时节点怎么处理。这里按动作逐条钉住，顺带覆盖一个相邻缺陷：
 * 驳回后重新派单时，上一轮的接单/到场时间必须清掉，否则新师傅的到场打卡会被幂等判断跳过。
 */
@IntegrationTest
class TicketExtendedStatusTest {

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
    private NotificationMapper notificationMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void workerMarksWaitingMaterialAndResumes() throws Exception {
        String student = givenToken("test-ext-wait-student", 1, ROLE_STUDENT, null);
        String worker = givenToken("test-ext-wait-worker", 2, ROLE_WORKER, List.of(1L));
        String otherWorker = givenToken("test-ext-wait-worker2", 2, ROLE_WORKER, List.of(2L));
        String admin = givenToken("test-ext-wait-admin", 3, ROLE_ADMIN, null);

        String ticketId = submitTicket(student);
        dispatch(admin, ticketId, "test-ext-wait-worker");
        postJson(worker, "/api/worker/tickets/" + ticketId + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(0));

        postJson(worker, "/api/worker/tickets/" + ticketId + "/wait-material",
                Map.of("reason", "需要更换的密封圈仓库没货"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(admin, ticketId)).isEqualTo(35);
        assertThat(actionsOf(ticketId)).contains("WAIT_MATERIAL");
        assertThat(logRemarks(ticketId)).contains("需要更换的密封圈仓库没货");
        assertThat(notificationCount(Long.parseLong(ticketId), "TICKET_WAITING_MATERIAL"))
                .as("学生要知道维修为什么停了").isGreaterThanOrEqualTo(1);

        // 不在这单可见范围内的师傅不能动它（2 号楼师傅看不到 1 号楼的单）
        postJson(otherWorker, "/api/worker/tickets/" + ticketId + "/resume", Map.of())
                .andExpect(jsonPath("$.code").value(20001));
        // 待料是师傅自己的状态，后勤不能替他从 35 走到 45
        postJson(worker, "/api/worker/tickets/" + ticketId + "/unrepairable", Map.of("reason", "不想修了"))
                .andExpect(jsonPath("$.code").value(20002));

        postJson(worker, "/api/worker/tickets/" + ticketId + "/resume", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(admin, ticketId)).isEqualTo(30);
        assertThat(actionsOf(ticketId)).contains("RESUME");
    }

    @Test
    void unrepairableThenAdminRedispatchesToAnotherWorker() throws Exception {
        String student = givenToken("test-ext-ur-student", 1, ROLE_STUDENT, null);
        String worker1 = givenToken("test-ext-ur-worker1", 2, ROLE_WORKER, List.of(1L));
        String worker2 = givenToken("test-ext-ur-worker2", 2, ROLE_WORKER, List.of(1L));
        String admin = givenToken("test-ext-ur-admin", 3, ROLE_ADMIN, null);

        String ticketId = submitTicket(student);
        dispatch(admin, ticketId, "test-ext-ur-worker1");
        postJson(worker1, "/api/worker/tickets/" + ticketId + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        postJson(worker1, "/api/worker/tickets/" + ticketId + "/arrive", Map.of("repairCode", "482913"))
                .andExpect(jsonPath("$.code").value(0));

        postJson(worker1, "/api/worker/tickets/" + ticketId + "/unrepairable",
                Map.of("reason", "预埋管爆裂，需要专业队进场"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(admin, ticketId)).isEqualTo(45);
        assertThat(ticketById(ticketId).getWorkerId()).as("45 保留原师傅，后勤才知道是谁判的").isNotNull();
        assertThat(notificationCount(Long.parseLong(ticketId), "TICKET_UNREPAIRABLE_ADMIN"))
                .as("45 是在等后勤处置，必须通知到后勤").isGreaterThanOrEqualTo(1);

        // 派回给原师傅 = 重置计时，不允许；要让他继续修走"恢复处理"
        postJson(admin, "/api/admin/tickets/" + ticketId + "/dispatch",
                Map.of("workerId", userIdOf("test-ext-ur-worker1")))
                .andExpect(jsonPath("$.code").value(10001));

        // 换人重新派单：上一轮的接单/到场时间必须清空
        postJson(admin, "/api/admin/tickets/" + ticketId + "/dispatch",
                Map.of("workerId", userIdOf("test-ext-ur-worker2")))
                .andExpect(jsonPath("$.code").value(0));
        Ticket redispatched = ticketById(ticketId);
        assertThat(redispatched.getStatus()).isEqualTo(20);
        assertThat(redispatched.getWorkerId()).isEqualTo(userIdOf("test-ext-ur-worker2"));
        assertThat(redispatched.getAcceptTime()).isNull();
        assertThat(redispatched.getArriveTime()).as("新师傅是新的一轮，旧的到场时间不能留").isNull();

        // 新师傅能正常接单、到场
        postJson(worker2, "/api/worker/tickets/" + ticketId + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        postJson(worker2, "/api/worker/tickets/" + ticketId + "/arrive", Map.of("repairCode", "482913"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(admin, ticketId)).isEqualTo(30);
    }

    @Test
    void adminResumesWaitingOrUnrepairableAndTimerIsBack() throws Exception {
        String student = givenToken("test-ext-resume-student", 1, ROLE_STUDENT, null);
        String worker = givenToken("test-ext-resume-worker", 2, ROLE_WORKER, List.of(1L));
        String admin = givenToken("test-ext-resume-admin", 3, ROLE_ADMIN, null);

        String ticketId = submitTicket(student);
        dispatch(admin, ticketId, "test-ext-resume-worker");
        postJson(worker, "/api/worker/tickets/" + ticketId + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        postJson(worker, "/api/worker/tickets/" + ticketId + "/unrepairable",
                Map.of("reason", "配件停产"))
                .andExpect(jsonPath("$.code").value(0));

        postJson(admin, "/api/admin/tickets/" + ticketId + "/resume", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(admin, ticketId)).isEqualTo(30);
        assertThat(ticketById(ticketId).getWorkerId()).isEqualTo(userIdOf("test-ext-resume-worker"));
        assertThat(stringRedisTemplate.opsForZSet().score("ticket:timeout:PROCESS", "1|" + ticketId))
                .as("恢复处理要按原派单时间重新登记处理超时").isNotNull();

        // 35 也可以由后勤恢复
        postJson(worker, "/api/worker/tickets/" + ticketId + "/wait-material", Map.of("reason", "等阀门"))
                .andExpect(jsonPath("$.code").value(0));
        postJson(admin, "/api/admin/tickets/" + ticketId + "/resume", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(admin, ticketId)).isEqualTo(30);
    }

    @Test
    void adminClosesUnrepairableAndRejectedTickets() throws Exception {
        String student = givenToken("test-ext-close-student", 1, ROLE_STUDENT, null);
        String worker = givenToken("test-ext-close-worker", 2, ROLE_WORKER, List.of(1L));
        String admin = givenToken("test-ext-close-admin", 3, ROLE_ADMIN, null);

        // 45 → 60
        String unrepairable = submitTicket(student);
        dispatch(admin, unrepairable, "test-ext-close-worker");
        postJson(worker, "/api/worker/tickets/" + unrepairable + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        postJson(worker, "/api/worker/tickets/" + unrepairable + "/unrepairable", Map.of("reason", "墙体结构问题"))
                .andExpect(jsonPath("$.code").value(0));
        postJson(admin, "/api/admin/tickets/" + unrepairable + "/close", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(admin, unrepairable)).isEqualTo(60);

        // 80 → 60
        String rejected = submitTicket(student);
        dispatch(admin, rejected, "test-ext-close-worker");
        postJson(worker, "/api/worker/tickets/" + rejected + "/reject", Map.of("reason", "不是电工的活"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(admin, rejected)).isEqualTo(80);
        postJson(admin, "/api/admin/tickets/" + rejected + "/close", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(admin, rejected)).isEqualTo(60);
    }

    @Test
    void studentCancelsAfterDispatchAndTimersAreCancelled() throws Exception {
        String student = givenToken("test-ext-cancel-student", 1, ROLE_STUDENT, null);
        String worker = givenToken("test-ext-cancel-worker", 2, ROLE_WORKER, List.of(1L));
        String admin = givenToken("test-ext-cancel-admin", 3, ROLE_ADMIN, null);

        // 20 → 70：刚派单还没接
        String toAccept = submitTicket(student);
        dispatch(admin, toAccept, "test-ext-cancel-worker");
        postJson(student, "/api/student/tickets/" + toAccept + "/cancel", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(admin, toAccept)).isEqualTo(70);
        assertThat(stringRedisTemplate.opsForZSet().score("ticket:timeout:ACCEPT", "1|" + toAccept))
                .as("撤单后未接单提醒必须撤销").isNull();
        assertThat(notificationCount(Long.parseLong(toAccept), "TICKET_CANCELED"))
                .as("师傅要知道这单被撤了").isGreaterThanOrEqualTo(1);

        // 30 → 70：处理中撤单
        String processing = submitTicket(student);
        dispatch(admin, processing, "test-ext-cancel-worker");
        postJson(worker, "/api/worker/tickets/" + processing + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        postJson(student, "/api/student/tickets/" + processing + "/cancel", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(admin, processing)).isEqualTo(70);
        assertThat(stringRedisTemplate.opsForZSet().score("ticket:timeout:PROCESS", "1|" + processing))
                .as("处理中撤单后处理超时节点必须撤销").isNull();

        // 80 → 70：驳回后学生放弃
        String rejected = submitTicket(student);
        dispatch(admin, rejected, "test-ext-cancel-worker");
        postJson(worker, "/api/worker/tickets/" + rejected + "/reject", Map.of("reason", "不是这栋楼的活"))
                .andExpect(jsonPath("$.code").value(0));
        postJson(student, "/api/student/tickets/" + rejected + "/cancel", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(admin, rejected)).isEqualTo(70);
    }

    @Test
    void mergeMarksSourceAndLeavesTargetUntouched() throws Exception {
        String student = givenToken("test-ext-merge-student", 1, ROLE_STUDENT, null);
        String worker = givenToken("test-ext-merge-worker", 2, ROLE_WORKER, List.of(1L));
        String admin = givenToken("test-ext-merge-admin", 3, ROLE_ADMIN, null);

        String source = submitTicket(student);
        String target = submitTicket(student);
        dispatch(admin, source, "test-ext-merge-worker");

        postJson(admin, "/api/admin/tickets/" + source + "/merge",
                Map.of("targetId", Long.parseLong(target), "remark", "同一个漏水点"))
                .andExpect(jsonPath("$.code").value(0));

        Ticket merged = ticketById(source);
        assertThat(merged.getStatus()).isEqualTo(75);
        assertThat(merged.getMergedIntoId()).isEqualTo(Long.parseLong(target));
        assertThat(merged.getWorkerId()).as("合并是终态，不再挂维修工").isNull();
        assertThat(merged.getCloseTime()).isNotNull();
        assertThat(ticketById(target).getStatus()).as("目标单不动").isEqualTo(10);
        assertThat(actionsOf(source)).contains("MERGE");
        assertThat(logRemarksOfSource(source)).anyMatch(r -> r.contains(ticketById(target).getTicketNo()));
        assertThat(notificationCount(Long.parseLong(source), "TICKET_MERGED"))
                .as("被合并单的学生要知道并到哪张单了").isGreaterThanOrEqualTo(1);

        // 管理端详情能直接看到目标单号
        JsonNode detail = getJson(admin, "/api/admin/tickets/" + source).path("data");
        assertThat(detail.path("mergedIntoNo").asText()).isEqualTo(ticketById(target).getTicketNo());

        // 合并到自己、并进已撤单的单，都要拒绝
        postJson(admin, "/api/admin/tickets/" + target + "/merge", Map.of("targetId", Long.parseLong(target)))
                .andExpect(jsonPath("$.code").value(10001));
        postJson(student, "/api/student/tickets/" + target + "/cancel", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        String another = submitTicket(student);
        postJson(admin, "/api/admin/tickets/" + another + "/merge", Map.of("targetId", Long.parseLong(target)))
                .andExpect(jsonPath("$.code").value(10001));
    }

    /**
     * 相邻缺陷回归：驳回（可能发生在到场之后、甚至完工之后）必须清掉上一轮的时间与结果，
     * 否则重新派单后新师傅的"到场打卡"会被 `arriveTime != null` 的幂等判断直接跳过。
     */
    @Test
    void rejectClearsPreviousCycleTimesSoNewWorkerCanArrive() throws Exception {
        String student = givenToken("test-ext-reject-student", 1, ROLE_STUDENT, null);
        String worker1 = givenToken("test-ext-reject-worker1", 2, ROLE_WORKER, List.of(1L));
        String worker2 = givenToken("test-ext-reject-worker2", 2, ROLE_WORKER, List.of(1L));
        String admin = givenToken("test-ext-reject-admin", 3, ROLE_ADMIN, null);

        String ticketId = submitTicket(student);
        dispatch(admin, ticketId, "test-ext-reject-worker1");
        postJson(worker1, "/api/worker/tickets/" + ticketId + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        postJson(worker1, "/api/worker/tickets/" + ticketId + "/arrive", Map.of("repairCode", "482913"))
                .andExpect(jsonPath("$.code").value(0));
        postJson(worker1, "/api/worker/tickets/" + ticketId + "/reject", Map.of("reason", "需要电工证，我没有"))
                .andExpect(jsonPath("$.code").value(0));

        Ticket rejected = ticketById(ticketId);
        assertThat(rejected.getStatus()).isEqualTo(80);
        assertThat(rejected.getWorkerId()).isNull();
        assertThat(rejected.getAcceptTime()).isNull();
        assertThat(rejected.getArriveTime()).isNull();
        assertThat(rejected.getArriveMinutes()).isNull();

        dispatch(admin, ticketId, "test-ext-reject-worker2");
        postJson(worker2, "/api/worker/tickets/" + ticketId + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        postJson(worker2, "/api/worker/tickets/" + ticketId + "/arrive", Map.of("repairCode", "482913"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(ticketById(ticketId).getArriveTime()).as("新师傅的到场时间必须重新记录").isNotNull();
    }

    // ==================== 工具 ====================

    private String submitTicket(String token) throws Exception {
        MvcResult result = postJson(token, "/api/student/tickets", Map.of(
                        "idempotencyKey", "test-ext-" + UUID.randomUUID(),
                        "repairCode", "482913", "categoryId", 1, "description", "扩展状态测试"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asText();
    }

    private void dispatch(String admin, String ticketId, String workerUsername) throws Exception {
        postJson(admin, "/api/admin/tickets/" + ticketId + "/dispatch",
                Map.of("workerId", userIdOf(workerUsername)))
                .andExpect(jsonPath("$.code").value(0));
    }

    private Ticket ticketById(String ticketId) {
        return ticketMapper.selectById(Long.parseLong(ticketId));
    }

    private int statusOf(String token, String ticketId) throws Exception {
        return getJson(token, "/api/admin/tickets/" + ticketId).path("data").path("status").asInt();
    }

    private List<String> actionsOf(String ticketId) {
        return ticketLogMapper.selectList(Wrappers.<TicketLog>lambdaQuery()
                        .eq(TicketLog::getTicketId, Long.parseLong(ticketId)))
                .stream().map(TicketLog::getAction).toList();
    }

    private List<String> logRemarks(String ticketId) {
        return ticketLogMapper.selectList(Wrappers.<TicketLog>lambdaQuery()
                        .eq(TicketLog::getTicketId, Long.parseLong(ticketId)))
                .stream().map(TicketLog::getRemark).filter(java.util.Objects::nonNull).toList();
    }

    private List<String> logRemarksOfSource(String ticketId) {
        return logRemarks(ticketId);
    }

    private long notificationCount(long ticketId, String type) {
        return notificationMapper.selectCount(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getTicketId, ticketId)
                .eq(Notification::getType, type));
    }

    private long userIdOf(String username) {
        return sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username)).getId();
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
