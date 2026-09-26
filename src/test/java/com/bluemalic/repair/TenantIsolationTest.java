package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.Paging;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TenantMapper;
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
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 多租户隔离验收（ADR-008）。造两个租户，验证"先按租户隔离、再按角色隔离"这两层真的都生效。
 *
 * <p>修的是这样一个洞：数据权限拦截器原本只有角色维度，后勤（ADMIN）直接豁免 → **跨租户全可见**；
 * 派单也没校验师傅属于哪个租户 → 能把 A 校的工单派给 B 校的师傅。
 *
 * <p>统计接口用 2020-06 这个固定历史窗口断言，避免本地库里 2026 的真实残留数据污染计数。
 */
@IntegrationTest
class TenantIsolationTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;
    private static final long ROLE_WORKER = 2L;
    private static final long ROLE_ADMIN = 3L;
    private static final String WINDOW = "?start=2020-06-01&end=2020-06-30";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long tenantAId;
    private long tenantBId;
    private long workerAId;
    private long workerBId;
    private String ticketA;
    private String ticketB;

    @BeforeEach
    void seedTwoTenants() throws Exception {
        tenantAId = tenantIdOf("gdou", false);
        tenantBId = tenantIdOf("test-tenant-b", true);

        long studentA = givenUser("test-ti-student-a", tenantAId, 1, ROLE_STUDENT);
        long studentB = givenUser("test-ti-student-b", tenantBId, 1, ROLE_STUDENT);
        workerAId = givenUser("test-ti-worker-a", tenantAId, 2, ROLE_WORKER);
        workerBId = givenUser("test-ti-worker-b", tenantBId, 2, ROLE_WORKER);
        // 管理员也在这里建好：login() 只负责登录，避免同一个用户名建两次撞 uk_tenant_username
        givenUser("test-ti-admin-a", tenantAId, 3, ROLE_ADMIN);
        givenUser("test-ti-admin-b", tenantBId, 3, ROLE_ADMIN);

        ticketA = givenTicket(tenantAId, studentA, "2020-06-05");
        ticketB = givenTicket(tenantBId, studentB, "2020-06-07");
    }

    @Test
    void adminSeesOnlyOwnTenantTickets() throws Exception {
        String adminA = login("gdou", "test-ti-admin-a", 3);
        String adminB = login("test-tenant-b", "test-ti-admin-b", 3);

        // ⚠️ **必须显式放大 pageSize**：这两条断言要证明的是"租户隔离"，不是分页。
        // 用默认档（10 条）时它会跟着**库里有多少工单**变红——本机库里有十来张演示工单，
        // 而本用例造的工单提交时间是 2020 年（排在最后），于是它掉出首页 → 本地红、CI 绿。
        // 这类"本地红 CI 绿"的假象比真失败更费时间，所以这里把话说死：把这一页要满。
        String url = "/api/admin/tickets?pageNum=1&pageSize=" + Paging.MAX_PAGE_SIZE;
        assertThat(listIds(adminA, url)).contains(ticketA).doesNotContain(ticketB);
        assertThat(listIds(adminB, url)).contains(ticketB).doesNotContain(ticketA);

        // 详情：跨租户的工单对外表现为"不存在"，不泄露"这条单存在但属于别人"
        getJson(adminA, "/api/admin/tickets/" + ticketB)
                .andExpect(jsonPath("$.code").value(20001));
    }

    @Test
    void dispatchRejectsWorkerFromAnotherTenant() throws Exception {
        String adminA = login("gdou", "test-ti-admin-a", 3);

        // 把 A 租户的工单派给 B 租户的师傅 → 与"师傅不存在"同一个错误，不告诉调用方差别
        postJson(adminA, "/api/admin/tickets/" + ticketA + "/dispatch", Map.of("workerId", workerBId))
                .andExpect(jsonPath("$.code").value(10001));

        // 同租户的师傅正常派单（确认上面的拦截没有误伤正常路径）
        postJson(adminA, "/api/admin/tickets/" + ticketA + "/dispatch", Map.of("workerId", workerAId))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void studentCannotSeeOtherTenantTickets() throws Exception {
        String studentB = login("test-tenant-b", "test-ti-student-b", 1);

        assertThat(listIds(studentB, "/api/student/tickets")).contains(ticketB).doesNotContain(ticketA);
        getJson(studentB, "/api/student/tickets/" + ticketA)
                .andExpect(jsonPath("$.code").value(20001));
    }

    @Test
    void statisticsAreScopedToTenant() throws Exception {
        String adminA = login("gdou", "test-ti-admin-a", 3);
        String adminB = login("test-tenant-b", "test-ti-admin-b", 3);

        assertThat(dataOf(adminA, "/api/admin/statistics/overview" + WINDOW).get("total").asInt()).isEqualTo(1);
        assertThat(dataOf(adminB, "/api/admin/statistics/overview" + WINDOW).get("total").asInt()).isEqualTo(1);
    }

    // ==================== 造数据 ====================

    private long tenantIdOf(String code, boolean create) {
        Tenant tenant = tenantMapper.selectOne(
                Wrappers.<Tenant>lambdaQuery().eq(Tenant::getCode, code));
        if (tenant != null) {
            return tenant.getId();
        }
        assertThat(create).as("租户 %s 不存在且未要求创建", code).isTrue();
        Tenant created = new Tenant();
        created.setCode(code);
        created.setName("隔离测试租户");
        tenantMapper.insert(created);
        return created.getId();
    }

    private long givenUser(String username, long tenantId, int userType, long roleId) {
        SysUser user = new SysUser();
        user.setTenantId(tenantId);
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
        return user.getId();
    }

    private String givenTicket(long tenantId, long studentId, String submitDate) {
        Ticket ticket = new Ticket();
        ticket.setTenantId(tenantId);
        ticket.setTicketNo("TI" + System.nanoTime());
        ticket.setStudentId(studentId);
        ticket.setBuildingId(1L);
        ticket.setRoom("1-101");
        ticket.setCategoryId(1L);
        ticket.setUrgency(1);
        ticket.setStatus(10);
        ticket.setSubmitTime(LocalDateTime.parse(submitDate + "T09:00:00"));
        ticketMapper.insert(ticket);
        return String.valueOf(ticket.getId());
    }

    // ==================== 工具 ====================

    /** 只登录（用户已在 @BeforeEach 建好）。 */
    private String login(String tenantCode, String username, int ignoredRoleId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", tenantCode, "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("tokenValue").asText();
    }

    private java.util.List<String> listIds(String token, String url) throws Exception {
        JsonNode list = dataOf(token, url).path("list");
        return StreamSupport.stream(list.spliterator(), false).map(n -> n.get("id").asText()).toList();
    }

    private JsonNode dataOf(String token, String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asInt()).as("接口应成功：" + url + " → " + body).isZero();
        return body.path("data");
    }

    private org.springframework.test.web.servlet.ResultActions getJson(String token, String url) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", "Bearer " + token));
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String token, String url, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}