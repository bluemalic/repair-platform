package com.bluemalic.repair;

import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
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
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 维修工的任务范围与排序（`docs/01` §4.2 的落地）。
 *
 * <p>这里盯的是**需求里那条容易被忽略的规则**：可见范围与操作权必须一致。
 * 过去的实现只按楼栋给范围，于是"跨楼栋强制派单"会造出一张**没有任何人能操作**的单——
 * 派单成功、师傅列表里看不到，只能等 24 小时未接单提醒。所以：
 *
 * <ol>
 *   <li>派给我的单（即使不在我负责的楼栋）→ 看得到，而且能接单/完工；</li>
 *   <li>我负责楼栋里的单（即使是别人的）→ 「本楼栋」视图看得到，但接不了（`20003`）；</li>
 *   <li>「我的任务」默认只给进行中，且按"紧急度置顶、同档先来的在前"排序。</li>
 * </ol>
 */
@IntegrationTest
class WorkerTaskScopeTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_ADMIN = 3L;
    private static final long ROLE_WORKER = 2L;

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
    private BuildingMapper buildingMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long workerId;
    private long otherWorkerId;
    private long adminId;
    private long myBuildingId;
    private long otherBuildingId;

    @BeforeEach
    void seed() throws Exception {
        workerId = givenUser("test-wts-worker", ROLE_WORKER);
        otherWorkerId = givenUser("test-wts-worker2", ROLE_WORKER);
        adminId = givenUser("test-wts-admin", ROLE_ADMIN);

        List<Building> buildings = buildingMapper.selectList(null);
        myBuildingId = buildings.get(0).getId();
        otherBuildingId = buildings.get(1).getId();

        // 我负责第一栋楼；第二栋楼不归我
        WorkerBuilding link = new WorkerBuilding();
        link.setTenantId(1L);
        link.setWorkerId(workerId);
        link.setBuildingId(myBuildingId);
        workerBuildingMapper.insert(link);
    }

    // ==================== 1. 跨楼栋强制派单：看得到、做得完 ====================

    @Test
    void ticketDispatchedToMeOutsideMyBuildingsIsVisibleAndOperable() throws Exception {
        String worker = login("test-wts-worker");
        String admin = login("test-wts-admin");
        // 待派单(10) → 派单 → 待接单(20)：派单只接受 10 / 80 这两个前置状态
        long ticketId = givenTicket(otherBuildingId, "10", 2, LocalDateTime.now());

        // 管理员跨楼栋强制派单：服务端放行（紧急抽调），并在流转日志里留痕
        postJson(admin, "/api/admin/tickets/" + ticketId + "/dispatch", Map.of("workerId", workerId))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(dispatchRemark(ticketId)).isEqualTo("跨楼栋强制派单（该师傅不负责本单楼栋）");

        // 师傅看得到（「我的任务」默认视图里），而且能接单——可见范围与操作权一致
        assertThat(listIds(worker, "/api/worker/tickets")).contains(String.valueOf(ticketId));
        postJson(worker, "/api/worker/tickets/" + ticketId + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ==================== 2. 本楼栋的单：看得到，但不是我的就动不了 ====================

    @Test
    void otherWorkersTicketInMyBuildingIsVisibleByBuildingScopeButNotAcceptable() throws Exception {
        String worker = login("test-wts-worker");
        long ticketId = givenTicket(myBuildingId, "20", 2, LocalDateTime.now());
        assignWorker(ticketId, otherWorkerId);

        assertThat(listIds(worker, "/api/worker/tickets?scope=building")).contains(String.valueOf(ticketId));
        // 默认视图（派给我的）里没有它
        assertThat(listIds(worker, "/api/worker/tickets")).doesNotContain(String.valueOf(ticketId));
        // 看得到也接不了：状态对、但不是派给我的 → 20003（见 TicketServiceImpl#isNotAssignee）
        postJson(worker, "/api/worker/tickets/" + ticketId + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(20003));
    }

    // ==================== 3. 默认视图的过滤与排序 ====================

    @Test
    void myTasksDefaultToActiveStatusesAndAreOrderedByUrgencyThenArrival() throws Exception {
        String worker = login("test-wts-worker");
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        long normalOld = givenTicket(myBuildingId, "30", 1, base.minusHours(5));
        long urgentNew = givenTicket(myBuildingId, "20", 3, base.minusHours(1));
        long normalNew = givenTicket(myBuildingId, "20", 1, base);
        long closed = givenTicket(myBuildingId, "60", 3, base);
        assignWorker(normalOld, workerId);
        assignWorker(urgentNew, workerId);
        assignWorker(normalNew, workerId);
        assignWorker(closed, workerId);

        List<String> ids = listIds(worker, "/api/worker/tickets?pageSize=100");

        // 终态（已关闭）不在默认视图里，显式筛状态才看得到
        assertThat(ids).doesNotContain(String.valueOf(closed));
        assertThat(listIds(worker, "/api/worker/tickets?status=60")).contains(String.valueOf(closed));
        // 紧急度高的置顶；同档内先提交的在前
        assertThat(ids).containsExactly(
                String.valueOf(urgentNew), String.valueOf(normalOld), String.valueOf(normalNew));
    }

    @Test
    void unknownScopeIsRejectedInsteadOfSilentlyFallingBack() throws Exception {
        String worker = login("test-wts-worker");

        mockMvc.perform(get("/api/worker/tickets?scope=everything")
                        .header("Authorization", "Bearer " + worker))
                .andExpect(jsonPath("$.code").value(10001));
    }

    // ==================== 工具 ====================

    private long givenUser(String username, long roleId) throws Exception {
        SysUser user = new SysUser();
        user.setTenantId(1L);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName(username);
        user.setUserType(roleId == ROLE_ADMIN ? UserType.ADMIN.getCode() : UserType.WORKER.getCode());
        user.setStatus(1);
        user.setMustChangePassword(0);
        sysUserMapper.insert(user);

        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(roleId);
        sysUserRoleMapper.insert(link);
        return user.getId();
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "gdou", "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("tokenValue").asText();
    }

    private long givenTicket(long buildingId, String status, int urgency, LocalDateTime submitTime) {
        Ticket ticket = new Ticket();
        ticket.setTenantId(1L);
        ticket.setTicketNo("WTS" + System.nanoTime());
        ticket.setStudentId(1L);
        ticket.setBuildingId(buildingId);
        ticket.setRoom("1-101");
        ticket.setCategoryId(1L);
        ticket.setDescription("测试用");
        ticket.setUrgency(urgency);
        ticket.setStatus(Integer.parseInt(status));
        ticket.setSubmitTime(submitTime);
        ticketMapper.insert(ticket);
        return ticket.getId();
    }

    private void assignWorker(long ticketId, long targetWorkerId) {
        Ticket update = new Ticket();
        update.setId(ticketId);
        update.setWorkerId(targetWorkerId);
        ticketMapper.updateById(update);
    }

    /** 派单那笔流转日志的备注（留痕文案就写在这里）。 */
    private String dispatchRemark(long ticketId) {
        return ticketLogMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<TicketLog>lambdaQuery()
                                .eq(TicketLog::getTicketId, ticketId)
                                .eq(TicketLog::getAction, "DISPATCH"))
                .stream().map(TicketLog::getRemark).findFirst().orElse(null);
    }

    private List<String> listIds(String token, String url) throws Exception {
        JsonNode list = dataOf(token, url).path("list");
        return StreamSupport.stream(list.spliterator(), false).map(n -> n.get("id").asText()).toList();
    }

    private JsonNode dataOf(String token, String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn();
        JsonNode body = readJson(result);
        assertThat(body.path("code").asInt()).as("接口应成功：" + url + " → " + body).isZero();
        return body.path("data");
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String token, String url, Map<String, ?> body)
            throws Exception {
        return mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
