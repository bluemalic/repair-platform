package com.bluemalic.repair;

import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketEvaluation;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TenantMapper;
import com.bluemalic.repair.mapper.TicketEvaluationMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统计看板口径测试（M3）。四个接口都断言到具体数字，因为它们最容易"看起来有数据但其实算错了"。
 *
 * <p><b>为什么用 2020-01 这个固定历史窗口</b>：本地库里可能残留演示数据（dev-seed、手工测试的工单），
 * 如果统计"近 30 天"就会把那些也算进去、断言无法稳定。固定到一个久远的空窗口后，断言只反映本测试造的数据。
 *
 * <p>造的数据（租户 1，窗口 2020-01-01 ~ 01-31）：
 * <pre>
 * A: 01-05 水电/1号楼/普通 到场30 完工50 评价5  有 ACCEPT_TIMEOUT  → 师傅 W1
 * B: 01-06 水电/2号楼/紧急 到场40 完工70 评价3  有 PROCESS_TIMEOUT → 师傅 W1
 * C: 01-06 家具/1号楼/普通 未派单未完工，无评价                    → 无师傅
 * D: 01-20 水电/1号楼/特急 到场50 完工90 评价4  无超时             → 师傅 W2
 * E: 租户 2 的工单（同窗口），用于验证统计不会跨租户
 * </pre>
 */
@IntegrationTest
class StatisticsTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_ADMIN = 3L;
    private static final String RANGE = "?start=2020-01-01&end=2020-01-31";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private TicketLogMapper ticketLogMapper;

    @Autowired
    private TicketEvaluationMapper ticketEvaluationMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long workerA;
    private long workerB;

    @BeforeEach
    void seedTickets() {
        workerA = givenWorker("测试师傅甲");
        workerB = givenWorker("测试师傅乙");

        Ticket a = ticket(1L, "2020-01-05", 30, 50, 1, 1, 1, workerA);
        givenEvaluation(a, 5);
        givenTimeoutLog(a, "ACCEPT_TIMEOUT");

        Ticket b = ticket(1L, "2020-01-06", 40, 70, 1, 2, 2, workerA);
        givenEvaluation(b, 3);
        givenTimeoutLog(b, "PROCESS_TIMEOUT");

        // 未派单未完工：计入总量与分布，但不进师傅工作量
        ticket(1L, "2020-01-06", null, null, 2, 1, 1, null);

        Ticket d = ticket(1L, "2020-01-20", 50, 90, 1, 1, 3, workerB);
        givenEvaluation(d, 4);

        givenTenantWithOneTicket();
    }

    @Test
    void overviewComputesTotalsAveragesAndTimeoutRate() throws Exception {
        String admin = givenAdmin("gdou");

        JsonNode data = getData(admin, "/api/admin/statistics/overview" + RANGE);

        assertThat(data.get("total").asInt()).isEqualTo(4);                       // 不含租户 2 的那条
        assertThat(data.get("avgResponseMinutes").asDouble()).isEqualTo(40.0);    // (30+40+50)/3
        assertThat(data.get("avgHandleMinutes").asDouble()).isEqualTo(70.0);      // (50+70+90)/3
        assertThat(data.get("avgScore").asDouble()).isEqualTo(4.0);               // (5+3+4)/3
        assertThat(data.get("timeoutCount").asInt()).isEqualTo(2);                // A 接单超时 + B 处理超时
        assertThat(data.get("timeoutRate").asDouble()).isEqualTo(50.0);
    }

    @Test
    void trendFillsMissingDaysAndGroupsByWeek() throws Exception {
        String admin = givenAdmin("gdou");

        JsonNode byDay = getData(admin, "/api/admin/statistics/trend" + RANGE + "&granularity=day");
        assertThat(byDay).hasSize(31);                                            // 01-01 ~ 01-31 每天一个点
        assertThat(countOn(byDay, "2020-01-05")).isEqualTo(1);
        assertThat(countOn(byDay, "2020-01-06")).isEqualTo(2);
        assertThat(countOn(byDay, "2020-01-20")).isEqualTo(1);
        assertThat(countOn(byDay, "2020-01-07")).isZero();                        // 没有工单的日期补 0

        JsonNode byWeek = getData(admin, "/api/admin/statistics/trend" + RANGE + "&granularity=week");
        // 01-05 是周日 → 归到 2019-12-30 那一周；01-06、01-20 都是周一
        assertThat(countOn(byWeek, "2019-12-30")).isEqualTo(1);
        assertThat(countOn(byWeek, "2020-01-06")).isEqualTo(2);
        assertThat(countOn(byWeek, "2020-01-20")).isEqualTo(1);
    }

    @Test
    void distributionSupportsThreeDimensions() throws Exception {
        String admin = givenAdmin("gdou");

        JsonNode byCategory = getData(admin, "/api/admin/statistics/distribution" + RANGE + "&dimension=category");
        assertThat(countOn(byCategory, "水电")).isEqualTo(3);
        assertThat(countOn(byCategory, "家具")).isEqualTo(1);

        JsonNode byBuilding = getData(admin, "/api/admin/statistics/distribution" + RANGE + "&dimension=building");
        assertThat(countOn(byBuilding, "1号楼")).isEqualTo(3);
        assertThat(countOn(byBuilding, "2号楼")).isEqualTo(1);

        JsonNode byUrgency = getData(admin, "/api/admin/statistics/distribution" + RANGE + "&dimension=urgency");
        assertThat(countOn(byUrgency, "普通")).isEqualTo(2);
        assertThat(countOn(byUrgency, "紧急")).isEqualTo(1);
        assertThat(countOn(byUrgency, "特急")).isEqualTo(1);
    }

    @Test
    void workerWorkloadCountsFinishedAndOnTimeRate() throws Exception {
        String admin = givenAdmin("gdou");

        JsonNode rows = getData(admin, "/api/admin/statistics/worker-workload" + RANGE);

        assertThat(rows).hasSize(2);
        JsonNode first = rows.get(0);                                             // 按完工数降序
        assertThat(first.get("workerName").asText()).isEqualTo("测试师傅甲");
        assertThat(first.get("finishedCount").asInt()).isEqualTo(2);
        assertThat(first.get("avgHandleMinutes").asDouble()).isEqualTo(60.0);     // (50+70)/2
        assertThat(first.get("processTimeoutCount").asInt()).isEqualTo(1);
        assertThat(first.get("onTimeRate").asDouble()).isEqualTo(50.0);           // (2-1)/2

        JsonNode second = rows.get(1);
        assertThat(second.get("workerName").asText()).isEqualTo("测试师傅乙");
        assertThat(second.get("onTimeRate").asDouble()).isEqualTo(100.0);
    }

    @Test
    void statisticsRequireViewPermissionAndRespectTenant() throws Exception {
        // 学生没有 statistics:view → 403
        String student = givenToken("test-stat-student", 1, 1L, "gdou");
        mockMvc.perform(get("/api/admin/statistics/overview" + RANGE).header("Authorization", "Bearer " + student))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));

        // 租户 2 的管理员只看到自己租户的 1 条
        String otherAdmin = givenAdmin("test-tenant-2");
        JsonNode data = getData(otherAdmin, "/api/admin/statistics/overview" + RANGE);
        assertThat(data.get("total").asInt()).isEqualTo(1);
    }

    // ==================== 造数据 ====================

    private Ticket ticket(Long tenantId, String submitDate, Integer arriveMinutes, Integer handleMinutes,
                          long categoryId, long buildingId, int urgency, Long workerId) {
        LocalDateTime submitAt = LocalDateTime.parse(submitDate + "T09:00:00");
        Ticket ticket = new Ticket();
        ticket.setTenantId(tenantId);
        ticket.setTicketNo("ST" + System.nanoTime());
        ticket.setStudentId(930000L);
        ticket.setWorkerId(workerId);
        ticket.setBuildingId(buildingId);
        ticket.setRoom("1-101");
        ticket.setCategoryId(categoryId);
        ticket.setUrgency(urgency);
        ticket.setStatus(handleMinutes == null ? 20 : 50);
        ticket.setSubmitTime(submitAt);
        ticket.setArriveMinutes(arriveMinutes);
        ticket.setHandleMinutes(handleMinutes);
        if (workerId != null) {
            ticket.setDispatchTime(submitAt.plusMinutes(5));
        }
        if (handleMinutes != null) {
            ticket.setFinishTime(submitAt.plusHours(2));
        }
        ticketMapper.insert(ticket);
        return ticket;
    }

    private void givenEvaluation(Ticket ticket, int score) {
        TicketEvaluation evaluation = new TicketEvaluation();
        evaluation.setTenantId(ticket.getTenantId());
        evaluation.setTicketId(ticket.getId());
        evaluation.setStudentId(ticket.getStudentId());
        evaluation.setScore(score);
        evaluation.setContent("统计测试");
        ticketEvaluationMapper.insert(evaluation);
    }

    private void givenTimeoutLog(Ticket ticket, String action) {
        TicketLog log = new TicketLog();
        log.setTenantId(ticket.getTenantId());
        log.setTicketId(ticket.getId());
        log.setFromStatus(ticket.getStatus());
        log.setToStatus(ticket.getStatus());
        log.setAction(action);
        log.setOperatorId(0L);
        ticketLogMapper.insert(log);
    }

    private long givenWorker(String realName) {
        SysUser worker = new SysUser();
        worker.setTenantId(1L);
        worker.setUsername("test-stat-worker-" + System.nanoTime() % 100000);
        worker.setPassword(passwordEncoder.encode(PASSWORD));
        worker.setRealName(realName);
        worker.setUserType(2);
        worker.setStatus(1);
        sysUserMapper.insert(worker);
        return worker.getId();
    }

    /** 租户 2 + 一条工单：验证统计按租户隔离。 */
    private void givenTenantWithOneTicket() {
        Tenant tenant = new Tenant();
        tenant.setCode("test-tenant-2");
        tenant.setName("统计测试租户");
        tenant.setStatus(1);
        tenantMapper.insert(tenant);
        ticket(tenant.getId(), "2020-01-10", 10, 20, 1, 1, 1, null);
    }

    // ==================== 工具 ====================

    private String givenAdmin(String tenantCode) throws Exception {
        return givenToken("test-stat-admin-" + tenantCode, 3, ROLE_ADMIN, tenantCode);
    }

    private String givenToken(String username, int userType, long roleId, String tenantCode) throws Exception {
        // 租户 id 是雪花值，不能假设它是 1/2；按 code 反查，才能让登录真的命中这个用户
        Tenant tenant = tenantMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Tenant>lambdaQuery()
                        .eq(Tenant::getCode, tenantCode));
        assertThat(tenant).as("租户 %s 应已存在", tenantCode).isNotNull();

        SysUser user = new SysUser();
        user.setTenantId(tenant.getId());
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
                                "tenantCode", tenantCode, "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("tokenValue").asText();
    }

    private JsonNode getData(String token, String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asInt()).as("接口应返回成功：" + url + " → " + body).isZero();
        return body.path("data");
    }

    /** 从 [{name/date, count}] 里取某一项的 count；找不到时返回 -1（便于断言失败时看清）。 */
    private int countOn(JsonNode rows, String key) {
        for (JsonNode row : rows) {
            if (key.equals(row.path("name").asText()) || key.equals(row.path("date").asText())) {
                return row.path("count").asInt();
            }
        }
        return -1;
    }
}