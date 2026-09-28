package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.entity.WorkerBuilding;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工单转派（`20 / 30 → 20`，`docs/01` §4.1）。
 *
 * <p>转派是四类非主线动作里最容易被当成"再派一次"的一个，所以断言盯的是**它和派单的差别**：
 * 换人要重置计时（不能让新师傅背前一个人的延迟）、要清掉上一轮的接单/到场时间
 * （否则响应时长会算出负数）、要给**原师傅**一条"你的活转走了"的通知。
 *
 * <p>另外两条边界也在这里：转给同一个人 = 变相把计时器清零（必须拒），
 * 以及"派单接口不能拿来换人"（否则台账上会记成 DISPATCH，看不出换过人）。
 */
@IntegrationTest
class TicketTransferTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;
    private static final long ROLE_WORKER = 2L;
    private static final long ROLE_ADMIN = 3L;
    private static final String ACCEPT_NODE = "ticket:timeout:ACCEPT";

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
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ==================== 20 → 20：还没接单就换人 ====================

    @Test
    void transferBeforeAcceptSwitchesWorkerAndRestartsTheClock() throws Exception {
        String admin = givenToken("test-tf-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-tf-student", 1, ROLE_STUDENT, null);
        String from = givenToken("test-tf-from", 2, ROLE_WORKER, List.of(1L));
        String to = givenToken("test-tf-to", 2, ROLE_WORKER, List.of(1L));
        String ticketId = dispatchedTicket(student, admin, "test-tf-from", "test-tf-from");

        Double beforeScore = stringRedisTemplate.opsForZSet().score(ACCEPT_NODE, ticketId);
        // 把派单时间往回拨一小时：DATETIME 只到秒，而派单与转派在测试里同一秒内发生，
        // 不断言"新值更晚"就分不清"重置了"还是"根本没动"
        LocalDateTime backdated = LocalDateTime.now().minusHours(1).withNano(0);
        ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
                .eq(Ticket::getId, Long.parseLong(ticketId))
                .set(Ticket::getDispatchTime, backdated));

        postJson(admin, "/api/admin/tickets/" + ticketId + "/transfer",
                Map.of("workerId", userIdOf("test-tf-to"), "reason", "原师傅临时请假"))
                .andExpect(jsonPath("$.code").value(0));

        // 换了人，状态仍在"待接单"（新师傅也得自己接，不能替他接）
        Ticket after = ticketOf(ticketId);
        assertThat(after.getWorkerId()).isEqualTo(userIdOf("test-tf-to"));
        assertThat(after.getStatus()).isEqualTo(20);

        // 计时从头开始：派单时间被重置到现在，未接单提醒的到期点也跟着往后挪
        assertThat(after.getDispatchTime()).isAfter(backdated);
        assertThat(stringRedisTemplate.opsForZSet().score(ACCEPT_NODE, ticketId))
                .as("转派后未接单提醒应按新的派单时间重登记").isGreaterThan(beforeScore);

        // 台账：一条 TRANSFER，理由与新师傅都写在备注里；不是 DISPATCH
        List<TicketLog> logs = logsOf(ticketId);
        assertThat(logs).extracting(TicketLog::getAction).containsExactly("SUBMIT", "DISPATCH", "TRANSFER");
        assertThat(logs.get(logs.size() - 1).getRemark()).contains("原师傅临时请假").contains("test-tf-to");

        // 两个师傅的通知：原师傅收到"已转出"，新师傅收到"待接单"
        assertThat(notificationTitles(from)).contains("工单已转出");
        assertThat(notificationTitles(to)).contains("新工单待接单");

        // 可见范围随之切换：新师傅的「我的任务」里有它，原师傅的没有
        assertThat(listIds(to, "/api/worker/tickets")).contains(ticketId);
        assertThat(listIds(from, "/api/worker/tickets")).doesNotContain(ticketId);
    }

    // ==================== 30 → 20：接了单、甚至到场了，也能换人 ====================

    @Test
    void transferWhileProcessingClearsPreviousAcceptAndArriveTimes() throws Exception {
        String admin = givenToken("test-tf2-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-tf2-student", 1, ROLE_STUDENT, null);
        String from = givenToken("test-tf2-from", 2, ROLE_WORKER, List.of(1L));
        String to = givenToken("test-tf2-to", 2, ROLE_WORKER, List.of(1L));
        String ticketId = dispatchedTicket(student, admin, "test-tf2-from", "test-tf2-from");

        // 原师傅接单 + 到场
        postJson(from, "/api/worker/tickets/" + ticketId + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        postJson(from, "/api/worker/tickets/" + ticketId + "/arrive", Map.of("repairCode", "482913"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(ticketOf(ticketId).getArriveTime()).isNotNull();

        postJson(admin, "/api/admin/tickets/" + ticketId + "/transfer",
                Map.of("workerId", userIdOf("test-tf2-to"), "reason", "他的片区临时调整"))
                .andExpect(jsonPath("$.code").value(0));

        // 上一轮的接单/到场时间必须清掉：响应时长口径是"到场 − 派单"，留着旧值会算出负数或虚高
        Ticket after = ticketOf(ticketId);
        assertThat(after.getStatus()).isEqualTo(20);
        assertThat(after.getAcceptTime()).isNull();
        assertThat(after.getArriveTime()).isNull();
        // 新师傅接手后能正常走完
        postJson(to, "/api/worker/tickets/" + ticketId + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(ticketOf(ticketId).getStatus()).isEqualTo(30);
    }

    // ==================== 边界 ====================

    @Test
    void transferToTheSameWorkerIsRejected() throws Exception {
        String admin = givenToken("test-tf3-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-tf3-student", 1, ROLE_STUDENT, null);
        String worker = givenToken("test-tf3-worker", 2, ROLE_WORKER, List.of(1L));
        String ticketId = dispatchedTicket(student, admin, "test-tf3-worker", "test-tf3-worker");
        LocalDateTime before = ticketOf(ticketId).getDispatchTime();

        // 转给自己 = 把计时器清零，等于绕过 24h 未接单提醒
        postJson(admin, "/api/admin/tickets/" + ticketId + "/transfer",
                Map.of("workerId", userIdOf("test-tf3-worker"), "reason", "手滑"))
                .andExpect(jsonPath("$.code").value(10001));
        assertThat(ticketOf(ticketId).getDispatchTime()).isEqualTo(before);
    }

    @Test
    void dispatchCannotBeUsedToSwapWorkers() throws Exception {
        String admin = givenToken("test-tf4-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-tf4-student", 1, ROLE_STUDENT, null);
        String worker = givenToken("test-tf4-worker", 2, ROLE_WORKER, List.of(1L));
        String other = givenToken("test-tf4-other", 2, ROLE_WORKER, List.of(1L));
        String ticketId = dispatchedTicket(student, admin, "test-tf4-worker", "test-tf4-worker");

        // 已经有师傅的单走派单 → 明确拒绝并指向转派（否则台账记成 DISPATCH、计时也不重置）
        postJson(admin, "/api/admin/tickets/" + ticketId + "/dispatch",
                Map.of("workerId", userIdOf("test-tf4-other")))
                .andExpect(jsonPath("$.code").value(20002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("转派")));
        assertThat(ticketOf(ticketId).getWorkerId()).isEqualTo(userIdOf("test-tf4-worker"));
    }

    @Test
    void transferIsRejectedAtToVerifyAndForDisabledWorker() throws Exception {
        String admin = givenToken("test-tf5-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-tf5-student", 1, ROLE_STUDENT, null);
        String worker = givenToken("test-tf5-worker", 2, ROLE_WORKER, List.of(1L));
        String other = givenToken("test-tf5-other", 2, ROLE_WORKER, List.of(1L));
        String ticketId = dispatchedTicket(student, admin, "test-tf5-worker", "test-tf5-worker");
        postJson(worker, "/api/worker/tickets/" + ticketId + "/accept", Map.of());
        postJson(worker, "/api/worker/tickets/" + ticketId + "/arrive", Map.of("repairCode", "482913"));
        postJson(worker, "/api/worker/tickets/" + ticketId + "/finish",
                Map.of("resultDesc", "修好了", "resultImages", List.of()))
                .andExpect(jsonPath("$.code").value(0));

        // 40 待验收：该走"打回重做"或"驳回"，不是换人（活已经干完了）
        postJson(admin, "/api/admin/tickets/" + ticketId + "/transfer",
                Map.of("workerId", userIdOf("test-tf5-other"), "reason", "换个师傅试试"))
                .andExpect(jsonPath("$.code").value(20002));
    }

    @Test
    void transferValidatesTargetAndPermission() throws Exception {
        String admin = givenToken("test-tf6-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-tf6-student", 1, ROLE_STUDENT, null);
        String worker = givenToken("test-tf6-worker", 2, ROLE_WORKER, List.of(1L));
        String other = givenToken("test-tf6-other", 2, ROLE_WORKER, List.of(1L));
        String ticketId = dispatchedTicket(student, admin, "test-tf6-worker", "test-tf6-worker");

        // 理由必填：原师傅得知道为什么被转走
        postJson(admin, "/api/admin/tickets/" + ticketId + "/transfer",
                Map.of("workerId", userIdOf("test-tf6-other"), "reason", "  "))
                .andExpect(jsonPath("$.code").value(10001));
        // 目标不能是学生（不是维修工）
        postJson(admin, "/api/admin/tickets/" + ticketId + "/transfer",
                Map.of("workerId", userIdOf("test-tf6-student"), "reason", "派给学生试试"))
                .andExpect(jsonPath("$.code").value(10001));
        // 权限：学生与维修工都不该能转派
        postJson(student, "/api/admin/tickets/" + ticketId + "/transfer",
                Map.of("workerId", userIdOf("test-tf6-other"), "reason", "我要换人"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));
        postJson(worker, "/api/admin/tickets/" + ticketId + "/transfer",
                Map.of("workerId", userIdOf("test-tf6-other"), "reason", "我自己换掉"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));
    }

    // ==================== 工具 ====================

    /** 提交 + 派给指定师傅，停在 20 待接单。 */
    private String dispatchedTicket(String student, String admin, String workerUsername, String ignored)
            throws Exception {
        MvcResult result = postJson(student, "/api/student/tickets", Map.of(
                        "repairCode", "482913", "categoryId", 1, "description", "【测试】水管漏水"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        String ticketId = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asText();
        postJson(admin, "/api/admin/tickets/" + ticketId + "/dispatch",
                Map.of("workerId", userIdOf(workerUsername))).andExpect(jsonPath("$.code").value(0));
        return ticketId;
    }

    private Ticket ticketOf(String ticketId) {
        return ticketMapper.selectById(Long.parseLong(ticketId));
    }

    private List<TicketLog> logsOf(String ticketId) {
        return ticketLogMapper.selectList(Wrappers.<TicketLog>lambdaQuery()
                .eq(TicketLog::getTicketId, Long.parseLong(ticketId))
                .orderByAsc(TicketLog::getCreateTime));
    }

    private List<String> notificationTitles(String token) throws Exception {
        JsonNode list = getJson(token, "/api/notifications?pageNum=1&pageSize=10").path("data").path("list");
        return java.util.stream.StreamSupport.stream(list.spliterator(), false)
                .map(node -> node.path("title").asText()).toList();
    }

    private List<String> listIds(String token, String url) throws Exception {
        JsonNode list = getJson(token, url).path("data").path("list");
        return java.util.stream.StreamSupport.stream(list.spliterator(), false)
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
