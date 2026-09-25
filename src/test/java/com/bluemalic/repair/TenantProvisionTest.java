package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.TicketStatus;
import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TenantMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.AccountService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 租户入驻（平台运营）+ 后勤管理员找回口令。
 *
 * <p>这个类里最值钱的是**平台域与租户域互不越界那三条**（平台调不了租户接口、租户管理员调不了
 * 平台接口、平台动不了自己）。它们守的是一个很容易"看起来做了"的功能：给平台角色只授一个权限码
 * 挡不住那些**没有权限码**的接口（{@code /api/categories} 等），必须靠拦截器把平台账号关在
 * {@code /api/platform/**} 里——这一点只测"平台调管理端接口被拒"是发现不了的。
 *
 * <p>其次是**停用租户**那一条：只改库不踢人，"停用"就只是个标签，已登录的人手上的 token
 * 默认还有 7 天有效期。
 *
 * <p>最后是**引导只建不覆盖**：覆盖的话每次重启都会把平台口令打回环境变量里的值。
 */
@IntegrationTest
class TenantProvisionTest {

    /** 平台自身的登录参数：租户编码固定 platform，账号 tenant_id = 0。 */
    private static final String PLATFORM_TENANT_CODE = "platform";
    private static final String TENANT_CODE = "gdou";
    private static final String PASSWORD = "Test@123456";
    private static final String INITIAL_PASSWORD = "Init@123456";
    private static final String CHANGED_PASSWORD = "Changed@9876";
    private static final long ROLE_ADMIN = 3L;
    private static final long ROLE_PLATFORM = 4L;
    private static final long TENANT_GD0U = 1L;

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
    private AccountService accountService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ==================== 开通学校 ====================

    @Test
    void provisionCreatesTenantAndFirstAdmin() throws Exception {
        String platform = givenPlatformToken("test-plat-provision");

        postJson(platform, "/api/platform/tenants", tenantBody("test-uni-1", "test-uni-1-admin"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.code").value("test-uni-1"))
                // create_time 是库里的默认值，回查一次才拿得到（否则开通接口比列表接口少一列）
                .andExpect(jsonPath("$.data.createTime").isNotEmpty());

        Tenant tenant = tenantByCode("test-uni-1");
        assertThat(tenant.getStatus()).isEqualTo(1);

        SysUser admin = userBy(tenant.getId(), "test-uni-1-admin");
        assertThat(admin.getUserType()).isEqualTo(UserType.ADMIN.getCode());
        // 平台告诉学校的口令是一次性凭证：建出来就必须带"首登改密"
        assertThat(admin.getMustChangePassword()).isEqualTo(1);

        // 能登录只说明 sys_user 建对了；roles/permissions 非空才说明 sys_user_role 也插对了
        String token = login("test-uni-1", "test-uni-1-admin", INITIAL_PASSWORD);
        MvcResult me = mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode meData = readJson(me).path("data");
        assertThat(meData.path("roles").get(0).asText()).isEqualTo("ADMIN");
        assertThat(meData.path("permissions")).isNotEmpty();
    }

    @Test
    void duplicateTenantCodeIsRejected() throws Exception {
        String platform = givenPlatformToken("test-plat-dup");
        postJson(platform, "/api/platform/tenants", tenantBody("test-uni-dup", "test-uni-dup-admin"))
                .andExpect(jsonPath("$.code").value(0));

        // 同一个编码再开一次：uk_code 兜底，报明确的业务错误而不是 500
        postJson(platform, "/api/platform/tenants", tenantBody("test-uni-dup", "test-uni-dup-admin2"))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("学校编码已存在"));
    }

    @Test
    void invalidTenantCodeIsRejected() throws Exception {
        String platform = givenPlatformToken("test-plat-badcode");

        // 编码是师生天天输入的登录参数，不允许出现大写/空格/中文这类容易打错的东西
        postJson(platform, "/api/platform/tenants", tenantBody("Test_Uni", "test-uni-bad-admin"))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("小写字母")));
    }

    // ==================== 数据隔离（端到端） ====================

    @Test
    void newTenantAdminCannotSeeOtherTenantTickets() throws Exception {
        // 租户 1 里有一条别人的工单
        givenTicket(TENANT_GD0U, 999999L, "test-other-tenant-room");

        String platform = givenPlatformToken("test-plat-isolation");
        postJson(platform, "/api/platform/tenants", tenantBody("test-uni-iso", "test-uni-iso-admin"))
                .andExpect(jsonPath("$.code").value(0));

        // 新租户的管理员先改掉初始口令，才过得了强制改密那一关
        mockMvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer "
                                + login("test-uni-iso", "test-uni-iso-admin", INITIAL_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", INITIAL_PASSWORD, "newPassword", CHANGED_PASSWORD))))
                .andExpect(jsonPath("$.code").value(0));

        String token = login("test-uni-iso", "test-uni-iso-admin", CHANGED_PASSWORD);
        MvcResult result = mockMvc.perform(get("/api/admin/tickets")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        // 后勤在本租户内不受角色限制，但**租户这一层始终生效**（ADR-008）：新学校看不到 gdou 的单
        assertThat(readJson(result).path("data").path("total").asLong()).isZero();
    }

    // ==================== 域收口：平台与租户互不越界 ====================

    @Test
    void platformCannotCallTenantApi() throws Exception {
        String platform = givenPlatformToken("test-plat-scope");

        // 带权限码的租户接口是被**权限注解**挡住的：Sa-Token 的 SaInterceptor 自己也校验注解，
        // 而它的 order 是 0、先于平台域收口（order 3）——所以这里是 NotPermissionException
        // → HTTP 403 + 10003，与下面那条（无权限码接口）的 200 不同。对前端是同一个 10003。
        mockMvc.perform(get("/api/admin/tickets").header("Authorization", "Bearer " + platform))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));
    }

    @Test
    void platformCannotCallTenantApiWithoutPermissionCode() throws Exception {
        String platform = givenPlatformToken("test-plat-scope-nocode");

        // 这一条才是域收口真正要解决的问题：/api/categories **没有任何权限码**，
        // 只授一个 tenant:manage 是挡不住它的——没有拦截器的话它会真的执行
        mockMvc.perform(get("/api/categories").header("Authorization", "Bearer " + platform))
                .andExpect(jsonPath("$.code").value(10003))
                .andExpect(jsonPath("$.message").value("平台运营账号只能访问平台运营接口"));

        // 自助接口（改密 / 登出 / 看当前用户）必须放行，否则平台账号改不了自己的初始口令
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + platform))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void tenantAdminCannotCallPlatformApi() throws Exception {
        String admin = givenTenantAdminToken("test-uni-admin-forbidden");

        // 这一条走的是 Sa-Token 的权限注解（NotPermissionException → HTTP 403 + 10003），
        // 与上一条的 HTTP 200 不同——两条路径的返回体里都是 10003，前端看到的是同一个错误
        mockMvc.perform(get("/api/platform/tenants").header("Authorization", "Bearer " + admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));
    }

    @Test
    void platformCannotTouchItself() throws Exception {
        String platform = givenPlatformToken("test-plat-self");

        // 平台自身（id = 0）不是学校：允许停用自己 = 把自己锁在门外，而它没有任何别的入口能回来
        putJson(platform, "/api/platform/tenants/0/status", Map.of("status", 0))
                .andExpect(jsonPath("$.code").value(10006))
                .andExpect(jsonPath("$.message").value("租户不存在"));

        assertThat(tenantById(0L).getStatus()).isEqualTo(1);
    }

    @Test
    void tenantListExcludesPlatformItself() throws Exception {
        String platform = givenPlatformToken("test-plat-list");

        MvcResult result = mockMvc.perform(get("/api/platform/tenants")
                        .header("Authorization", "Bearer " + platform))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode list = readJson(result).path("data").path("list");
        // 断言的是"平台自身不在列表里"，**不是"列表恰好有几条"**：后者会让这条测试因为
        // 本地库里多了一所学校（比如演示时手工开的）而失败，而那与这条不变量毫无关系。
        // （实测踩过：本机留着验证时开的「演示学校B」，这条断言就红了，而线上 CI 是绿的）
        List<String> codes = new ArrayList<>();
        list.forEach(row -> codes.add(row.path("code").asText()));
        assertThat(codes).as("平台自身不该出现在学校列表里").doesNotContain("platform");
        assertThat(codes).contains(TENANT_CODE);

        // 按名字搜也搜不到它
        mockMvc.perform(get("/api/platform/tenants?keyword=platform")
                        .header("Authorization", "Bearer " + platform))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // ==================== 停用租户 ====================

    @Test
    void disableTenantBlocksLoginAndKicksOutOnlineUsers() throws Exception {
        String platform = givenPlatformToken("test-plat-disable");
        postJson(platform, "/api/platform/tenants", tenantBody("test-uni-off", "test-uni-off-admin"))
                .andExpect(jsonPath("$.code").value(0));
        long tenantId = tenantByCode("test-uni-off").getId();

        mockMvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer "
                                + login("test-uni-off", "test-uni-off-admin", INITIAL_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", INITIAL_PASSWORD, "newPassword", CHANGED_PASSWORD))))
                .andExpect(jsonPath("$.code").value(0));
        String token = login("test-uni-off", "test-uni-off-admin", CHANGED_PASSWORD);
        mockMvc.perform(get("/api/admin/tickets").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));

        putJson(platform, "/api/platform/tenants/" + tenantId + "/status", Map.of("status", 0))
                .andExpect(jsonPath("$.code").value(0));

        // ① 已经登录的人立刻被踢：登录态在 Redis，不受 tenant.status 影响，光改库挡不住他
        mockMvc.perform(get("/api/admin/tickets").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10002));

        // ② 之后也登不进来：登录接口要求租户是启用中
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "test-uni-off", "username", "test-uni-off-admin",
                                "password", CHANGED_PASSWORD))))
                .andExpect(jsonPath("$.code").value(30003));
    }

    // ==================== 管理员口令 ====================

    @Test
    void platformResetsAdminPasswordAndForcesChange() throws Exception {
        String platform = givenPlatformToken("test-plat-reset");
        postJson(platform, "/api/platform/tenants", tenantBody("test-uni-reset", "test-uni-reset-admin"))
                .andExpect(jsonPath("$.code").value(0));
        long tenantId = tenantByCode("test-uni-reset").getId();
        long adminId = userBy(tenantId, "test-uni-reset-admin").getId();

        putJson(platform, "/api/platform/tenants/" + tenantId + "/admins/" + adminId + "/password",
                Map.of("password", "Reset@123456"))
                .andExpect(jsonPath("$.code").value(0));

        // 旧口令失效、新口令可用，且重置出来的口令同样是一次性的
        assertThat(userBy(tenantId, "test-uni-reset-admin").getMustChangePassword()).isEqualTo(1);
        String token = login("test-uni-reset", "test-uni-reset-admin", "Reset@123456");
        mockMvc.perform(get("/api/admin/tickets").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(10003))
                .andExpect(jsonPath("$.message").value("请先修改初始口令"));

        // 路径里的租户与用户必须对得上：否则平台能借 A 学校的路径改 B 学校管理员的口令
        putJson(platform, "/api/platform/tenants/" + tenantId + "/admins/999999/password",
                Map.of("password", "Reset@123456"))
                .andExpect(jsonPath("$.code").value(10006))
                .andExpect(jsonPath("$.message").value("管理员不存在"));
    }

    @Test
    void addAdminToExistingTenant() throws Exception {
        String platform = givenPlatformToken("test-plat-addadmin");
        postJson(platform, "/api/platform/tenants", tenantBody("test-uni-add", "test-uni-add-admin"))
                .andExpect(jsonPath("$.code").value(0));
        long tenantId = tenantByCode("test-uni-add").getId();

        postJson(platform, "/api/platform/tenants/" + tenantId + "/admins", Map.of(
                "username", "test-uni-add-admin2", "realName", "管理员乙",
                "password", INITIAL_PASSWORD))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.mustChangePassword").value(true));

        // 列表里两个管理员都在，且都能登进来（第二个是"学校唯一的管理员离职"的兜底）
        MvcResult result = mockMvc.perform(get("/api/platform/tenants/" + tenantId + "/admins")
                        .header("Authorization", "Bearer " + platform))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        assertThat(readJson(result).path("data").size()).isEqualTo(2);
        assertThat(login("test-uni-add", "test-uni-add-admin2", INITIAL_PASSWORD)).isNotBlank();
    }

    // ==================== 平台账号的引导 ====================

    @Test
    void bootstrapCreatesPlatformAccountOnceAndNeverOverwrites() throws Exception {
        SysUser created = accountService.ensurePlatformAccount(
                "test-plat-boot", "Boot@123456", "平台运营");
        assertThat(created).isNotNull();
        assertThat(created.getTenantId()).isZero();
        assertThat(created.getUserType()).isEqualTo(UserType.PLATFORM.getCode());
        assertThat(created.getMustChangePassword()).isEqualTo(1);
        assertThat(roleIdsOf(created.getId())).containsExactly(ROLE_PLATFORM);

        // 第二次引导（换了口令、换了名字）：账号已存在就什么都不做
        assertThat(accountService.ensurePlatformAccount("test-plat-boot", "Other@999999", "别的名字"))
                .isNull();

        SysUser stored = sysUserMapper.selectById(created.getId());
        assertThat(passwordEncoder.matches("Boot@123456", stored.getPassword())).isTrue();
        assertThat(passwordEncoder.matches("Other@999999", stored.getPassword())).isFalse();
        assertThat(stored.getRealName()).isEqualTo("平台运营");
    }

    @Test
    void bootstrappedPlatformAccountMustChangePasswordFirst() throws Exception {
        accountService.ensurePlatformAccount("test-plat-boot-force", INITIAL_PASSWORD, "平台运营");

        String token = login(PLATFORM_TENANT_CODE, "test-plat-boot-force", INITIAL_PASSWORD);

        // 引导出来的平台账号同样受"首登强制改密"约束：先改口令，之后才谈得上开学校
        mockMvc.perform(get("/api/platform/tenants").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(10003))
                .andExpect(jsonPath("$.message").value("请先修改初始口令"));

        mockMvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", INITIAL_PASSWORD, "newPassword", CHANGED_PASSWORD))))
                .andExpect(jsonPath("$.code").value(0));

        String newToken = login(PLATFORM_TENANT_CODE, "test-plat-boot-force", CHANGED_PASSWORD);
        mockMvc.perform(get("/api/platform/tenants").header("Authorization", "Bearer " + newToken))
                .andExpect(jsonPath("$.code").value(0));
    }

    // ==================== 工具 ====================

    private Map<String, String> tenantBody(String code, String adminUsername) {
        return Map.of("name", "测试大学-" + code, "code", code,
                "contact", "后勤处", "phone", "0759-1111111",
                "adminUsername", adminUsername, "adminRealName", "管理员",
                "adminPassword", INITIAL_PASSWORD);
    }

    /** 平台运营账号 + token。租户编码固定 platform，账号挂在 tenant_id = 0 上。 */
    private String givenPlatformToken(String username) throws Exception {
        givenAccount(0L, username, UserType.PLATFORM.getCode(), ROLE_PLATFORM);
        return login(PLATFORM_TENANT_CODE, username, PASSWORD);
    }

    /** 租户 1（gdou）里的后勤管理员 + token。 */
    private String givenTenantAdminToken(String username) throws Exception {
        givenAccount(TENANT_GD0U, username, UserType.ADMIN.getCode(), ROLE_ADMIN);
        return login(TENANT_CODE, username, PASSWORD);
    }

    /** 直接造账号 + 角色关联（不走接口：平台账号根本没有创建接口，这是唯一的造法）。 */
    private void givenAccount(long tenantId, String username, int userType, long roleId) {
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

    private void givenTicket(long tenantId, long studentId, String room) {
        Ticket ticket = new Ticket();
        ticket.setTenantId(tenantId);
        ticket.setTicketNo("WX-TEST-" + System.nanoTime());
        ticket.setStudentId(studentId);
        ticket.setBuildingId(1L);
        ticket.setRoom(room);
        ticket.setCategoryId(1L);
        ticket.setDescription("测试工单");
        ticket.setStatus(TicketStatus.TO_DISPATCH.getCode());
        ticket.setSubmitTime(LocalDateTime.now());
        ticketMapper.insert(ticket);
    }

    private Tenant tenantByCode(String code) {
        Tenant tenant = tenantMapper.selectOne(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getCode, code));
        assertThat(tenant).as("租户 %s 应存在", code).isNotNull();
        return tenant;
    }

    private Tenant tenantById(long id) {
        Tenant tenant = tenantMapper.selectById(id);
        assertThat(tenant).as("租户 %s 应存在", id).isNotNull();
        return tenant;
    }

    private SysUser userBy(long tenantId, String username) {
        SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getUsername, username));
        assertThat(user).as("账号 %s 应存在", username).isNotNull();
        return user;
    }

    private List<Long> roleIdsOf(long userId) {
        return sysUserRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .toList();
    }

    private String login(String tenantCode, String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", tenantCode, "username", username, "password", password))))
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

    private org.springframework.test.web.servlet.ResultActions putJson(String token, String url, Map<String, ?> body)
            throws Exception {
        return mockMvc.perform(put(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
