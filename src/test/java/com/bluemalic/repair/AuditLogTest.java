package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.AuditAction;
import com.bluemalic.repair.entity.AuditLog;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.mapper.AuditLogMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TenantMapper;
import com.bluemalic.repair.service.AccountService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 操作审计日志（`docs/01` §4.4）。
 *
 * <p>审计最容易出的两类问题，这里各钉一条：
 * <ul>
 *   <li><b>漏记</b>：显式调用（不用 AOP）的代价就是可能忘，所以每类操作都有一条断言——
 *       "改了什么都有记录"这件事只能靠测试守；</li>
 *   <li><b>把不该记的记了</b>：口令明文绝不落库（AGENTS §5.9）——断言里直接**拿口令字符串去搜整行**，
 *       搜不到才算过。手机号同理，只记"改过"不记值。</li>
 * </ul>
 */
@IntegrationTest
class AuditLogTest {

    private static final String PASSWORD = "Test@123456";
    private static final String NEW_PASSWORD = "Test@654321";
    private static final long ROLE_ADMIN = 3L;
    private static final long ROLE_WORKER = 2L;
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
    private TenantMapper tenantMapper;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private AccountService accountService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 账号类 ====================

    @Test
    void workerCreateUpdateAndBuildingsAreAudited() throws Exception {
        String admin = givenToken("test-au-admin", 3, ROLE_ADMIN);

        // 新增
        MvcResult created = postJson(admin, "/api/admin/workers",
                Map.of("username", "test-au-w1", "realName", "审计师傅", "password", PASSWORD))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        String workerId = readJson(created).path("data").path("id").asText();
        AuditLog create = latest(AuditAction.WORKER_CREATE);
        assertThat(create.getOperatorName()).as("操作人姓名要记快照").isEqualTo("test-au-admin");
        assertThat(create.getTargetName()).isEqualTo("test-au-w1");
        assertThat(create.getDetail()).contains("test-au-w1").contains("审计师傅");
        assertThat(create.getOperatorId()).isEqualTo(userIdOf("test-au-admin"));

        // 修改：停用 + 重置口令 → 摘要里列出改了哪些字段，**但不含口令本身**
        mockMvc.perform(put("/api/admin/workers/" + workerId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "realName", "审计师傅", "status", 0, "password", NEW_PASSWORD))))
                .andExpect(jsonPath("$.code").value(0));
        AuditLog update = latest(AuditAction.WORKER_UPDATE);
        assertThat(update.getDetail()).contains("状态 启用→停用").contains("重置口令");
        assertThat(update.getDetail()).as("口令明文绝不落库").doesNotContain(NEW_PASSWORD);

        // 设置负责楼栋：记楼栋名而不是 ID 列表
        mockMvc.perform(put("/api/admin/workers/" + workerId + "/buildings")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("buildingIds", List.of(1L)))))
                .andExpect(jsonPath("$.code").value(0));
        AuditLog buildings = latest(AuditAction.WORKER_BUILDINGS);
        assertThat(buildings.getDetail()).contains("负责楼栋").contains("楼");
    }

    @Test
    void studentImportAndSelfPasswordChangeAreAudited() throws Exception {
        String admin = givenToken("test-au-sadmin", 3, ROLE_ADMIN);

        // 名单是"粘贴的文本"（每行 学号,姓名），不是数组——前端就是这么传的
        postJson(admin, "/api/admin/students/import", Map.of(
                        "rows", "test-au-s1,审计学生甲\ntest-au-s2,审计学生乙",
                        "password", PASSWORD))
                .andExpect(jsonPath("$.code").value(0));
        AuditLog imported = latest(AuditAction.STUDENT_IMPORT);
        assertThat(imported.getDetail()).contains("新增 2 个");
        assertThat(imported.getTargetId()).as("批量导入没有单一目标").isNull();

        // 本人改密：记一条账号安全事件（操作人就是自己）
        String student = login("test-au-s1", PASSWORD);
        mockMvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", PASSWORD, "newPassword", NEW_PASSWORD))))
                .andExpect(jsonPath("$.code").value(0));
        AuditLog changed = latest(AuditAction.PASSWORD_CHANGE_SELF);
        // 操作人记的是**姓名快照**（realName），不是登录名
        assertThat(changed.getOperatorName()).isEqualTo("审计学生甲");
        assertThat(changed.getDetail()).doesNotContain(NEW_PASSWORD);
    }

    // ==================== 基础数据 ====================

    @Test
    void buildingChangesAreAudited() throws Exception {
        String admin = givenToken("test-au-badmin", 3, ROLE_ADMIN);

        MvcResult created = postJson(admin, "/api/admin/buildings",
                Map.of("name", "审计楼", "area", "东区", "sort", 9))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        String buildingId = readJson(created).path("data").path("id").asText();
        assertThat(latest(AuditAction.BUILDING_CREATE).getTargetName()).isEqualTo("审计楼");

        mockMvc.perform(put("/api/admin/buildings/" + buildingId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "审计楼（改名）", "area", "东区", "sort", 9, "status", 0))))
                .andExpect(jsonPath("$.code").value(0));
        AuditLog update = latest(AuditAction.BUILDING_UPDATE);
        assertThat(update.getDetail()).contains("审计楼").contains("改名").contains("启用→停用");
    }

    // ==================== 平台运营：记在 tenant_id = 0 上 ====================

    @Test
    void platformOperationsAreAuditedUnderTenantZero() throws Exception {
        SysUser platformUser = accountService.ensurePlatformAccount("test-au-platform", PASSWORD, "平台运营测试");
        // 引导建的平台账号带"首登强制改密"，而那个拦截器只放行 /api/auth/**（否则会拿到 10003）
        // ——测试里直接把标记清掉，避免多绕一次改密流程
        accountService.clearMustChangePassword(platformUser.getId(), 0L);
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "platform", "username", "test-au-platform", "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        String platform = readJson(loginResult).path("data").path("tokenValue").asText();

        String code = "test-au-school-" + System.nanoTime() % 100000;
        postJson(platform, "/api/platform/tenants", Map.of(
                        "name", "审计测试学校", "code", code,
                        "adminUsername", "test-au-school-admin", "adminRealName", "测试管理员",
                        "adminPassword", PASSWORD))
                .andExpect(jsonPath("$.code").value(0));

        AuditLog provision = latest(AuditAction.TENANT_PROVISION);
        assertThat(provision.getTenantId()).as("平台运营的操作落 tenant_id = 0").isZero();
        assertThat(provision.getDetail()).contains("审计测试学校").contains(code);
        assertThat(provision.getDetail()).as("口令明文绝不落库").doesNotContain(PASSWORD);
    }

    // ==================== 查询接口 ====================

    @Test
    void queryFiltersByActionOperatorAndDateRange() throws Exception {
        String admin = givenToken("test-au-qadmin", 3, ROLE_ADMIN);
        postJson(admin, "/api/admin/workers",
                Map.of("username", "test-au-q1", "realName", "查询师傅", "password", PASSWORD))
                .andExpect(jsonPath("$.code").value(0));

        // 动作筛选：查 WORKER_CREATE 不该混进别的动作
        JsonNode filtered = getJson(admin, "/api/admin/audit-logs?pageNum=1&pageSize=50&action=WORKER_CREATE");
        assertThat(filtered.path("data").path("total").asInt()).isPositive();
        for (JsonNode row : filtered.path("data").path("list")) {
            assertThat(row.path("action").asText()).isEqualTo("WORKER_CREATE");
            assertThat(row.path("actionLabel").asText()).isEqualTo("新增维修工");
        }

        // 操作人筛选
        JsonNode byOperator = getJson(admin, "/api/admin/audit-logs?operatorKeyword=test-au-qadmin");
        assertThat(byOperator.path("data").path("total").asInt()).isPositive();

        // 日期区间是"含首含尾"：把一条记录挪到昨天，今天筛不到、昨天筛得到
        AuditLog row = latest(AuditAction.WORKER_CREATE);
        jdbcTemplate.update("UPDATE audit_log SET create_time = ? WHERE id = ?",
                LocalDateTime.now().minusDays(1).withNano(0), row.getId());
        String today = java.time.LocalDate.now().toString();
        String yesterday = java.time.LocalDate.now().minusDays(1).toString();
        assertThat(idsOf(getJson(admin, "/api/admin/audit-logs?pageSize=50&startDate=" + today)))
                .as("按今天筛，昨天的操作不该出现").doesNotContain(String.valueOf(row.getId()));
        assertThat(idsOf(getJson(admin, "/api/admin/audit-logs?pageSize=50&startDate=" + yesterday
                + "&endDate=" + yesterday)))
                .as("按昨天筛，它必须在（含尾边界）").contains(String.valueOf(row.getId()));
    }

    @Test
    void auditLogIsVisibleToAdminsOfOwnTenantOnly() throws Exception {
        String admin = givenToken("test-au-scope-admin", 3, ROLE_ADMIN);
        postJson(admin, "/api/admin/workers",
                Map.of("username", "test-au-scope1", "realName", "隔离师傅", "password", PASSWORD))
                .andExpect(jsonPath("$.code").value(0));

        // 另一个租户的管理员：一条记录都看不到（audit_log 不在拦截器名单里，租户条件是显式写的）
        long tenantB = givenTenant("test-au-tenant-b");
        givenUserInTenant("test-au-b-admin", tenantB, 3, ROLE_ADMIN);
        String adminB = loginInTenant("test-au-tenant-b", "test-au-b-admin");
        JsonNode otherTenant = getJson(adminB, "/api/admin/audit-logs?pageNum=1&pageSize=50");
        assertThat(otherTenant.path("data").path("total").asInt()).as("B 租户看不到 A 租户的审计").isZero();

        // 越权：学生与维修工都没有 audit:list
        String student = givenToken("test-au-scope-student", 1, ROLE_STUDENT);
        String worker = givenToken("test-au-scope-worker", 2, ROLE_WORKER);
        for (String token : List.of(student, worker)) {
            mockMvc.perform(get("/api/admin/audit-logs").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(10003));
        }
    }

    // ==================== 工具 ====================

    /** 最近一条指定动作的审计（倒序取第一条）。 */
    private AuditLog latest(String action) {
        List<AuditLog> rows = auditLogMapper.selectList(Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getAction, action)
                .orderByDesc(AuditLog::getCreateTime)
                .orderByDesc(AuditLog::getId));
        assertThat(rows).as("没有找到动作 " + action + " 的审计记录").isNotEmpty();
        return rows.get(0);
    }

    private List<String> idsOf(JsonNode pageResult) {
        return java.util.stream.StreamSupport.stream(pageResult.path("data").path("list").spliterator(), false)
                .map(node -> node.path("id").asText()).toList();
    }

    private long givenTenant(String code) {
        Tenant tenant = new Tenant();
        tenant.setName("审计隔离测试学校");
        tenant.setCode(code);
        tenant.setStatus(1);
        tenantMapper.insert(tenant);
        return tenant.getId();
    }

    private void givenUserInTenant(String username, long tenantId, int userType, long roleId) {
        SysUser user = new SysUser();
        user.setTenantId(tenantId);
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
    }

    private String loginInTenant(String tenantCode, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", tenantCode, "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("tokenValue").asText();
    }

    private String givenToken(String username, int userType, long roleId) throws Exception {
        givenUserInTenant(username, 1L, userType, roleId);
        return login(username, PASSWORD);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "gdou", "username", username, "password", password))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("tokenValue").asText();
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
        return readJson(result);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
