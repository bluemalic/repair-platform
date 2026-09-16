package com.bluemalic.repair;

import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证与鉴权的端到端验证。
 *
 * <p>用 MockMvc 而不是真起服务器：MockMvc 的请求跑在测试**同一个线程**里，
 * 所以测试事务里插入的用户（尚未提交）对请求可见，并且照样经过过滤器与拦截器链。
 * 若用 {@code webEnvironment = RANDOM_PORT}，请求在另一个线程，未提交的数据看不到，
 * 测试就只能靠预置数据，写不干净。
 *
 * <p>鉴权探针接口见顶层类 {@link ProbeController}——**不要再把它 @Import 进来**，
 * 那会改变本类的上下文缓存键、多起一个 Spring 上下文，触发 {@link IntegrationTest} 里说明的问题。
 *
 * <p>角色 / 权限点用建表脚本里的种子数据（角色 1 学生、3 后勤管理），
 * 只由测试自己造"用户"和"用户-角色关联"。
 */
@IntegrationTest
class AuthControllerTest {

    private static final String TENANT_CODE = "gdou";
    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;
    private static final long ROLE_ADMIN = 3L;

    // 测试用的登录名统一带 test- 前缀，与 docs/dev-seed.sql 的演示账号（admin / worker01 /
    // 20260001）隔开。否则本地执行过 dev-seed 后，测试插入同名用户会撞 sys_user 的
    // 租户内唯一键 uk_tenant_username，测试就会莫名其妙变红。

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void loginThenCurrentUserCarriesRolesAndPermissions() throws Exception {
        givenUser("test-admin", 3, ROLE_ADMIN);

        String token = login("test-admin", PASSWORD);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("test-admin"))
                .andExpect(jsonPath("$.data.roles[0]").value("ADMIN"))
                // 权限码来自 sys_role_permission 种子数据，能拿到说明 StpInterfaceImpl 生效了
                .andExpect(jsonPath("$.data.permissions").isNotEmpty());
    }

    @Test
    void wrongPasswordIsRejectedWithLoginFailed() throws Exception {
        givenUser("test-wrong-pwd", 3, ROLE_STUDENT);

        mockMvc.perform(loginRequest("test-wrong-pwd", "wrong-password"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(30001));
    }

    @Test
    void unknownTenantIsRejected() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "tenantCode", "not-exist", "username", "anyone", "password", PASSWORD));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(30003));
    }

    @Test
    void disabledAccountIsRejected() throws Exception {
        SysUser user = givenUser("test-disabled", 3, ROLE_STUDENT);
        user.setStatus(0);
        sysUserMapper.updateById(user);

        mockMvc.perform(loginRequest("test-disabled", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(30002));
    }

    @Test
    void protectedEndpointWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10002));
    }

    @Test
    void permissionAnnotationBlocksStudentButAllowsAdmin() throws Exception {
        givenUser("test-student-probe", 1, ROLE_STUDENT);
        givenUser("test-admin-probe", 3, ROLE_ADMIN);

        String studentToken = login("test-student-probe", PASSWORD);
        String adminToken = login("test-admin-probe", PASSWORD);

        // 学生没有 ticket:dispatch，注解校验应拦下 → 403
        mockMvc.perform(get("/api/admin/probe/dispatch").header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));

        // 后勤管理有该权限 → 放行
        mockMvc.perform(get("/api/admin/probe/dispatch").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    /**
     * 客户端用错方式访问，不能被算成服务端故障。
     *
     * <p>这两类曾经落到兜底的 {@code Exception} 处理器上，表现成 500 + {@code 10005} +
     * 一整段 ERROR 堆栈——最典型的触发是把接口地址粘进浏览器地址栏（浏览器只会发 GET）。
     * 现在的契约是 405 / 415 + {@code 10006}，日志只在 DEBUG 记一行。
     */
    @Test
    void wrongMethodOrContentTypeIsNotServerError() throws Exception {
        // GET 一个只接受 POST 的接口 → 405
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(10006));

        // 请求体不是它认识的类型（登录要 JSON）→ 415
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.TEXT_PLAIN).content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(10006));
    }

    private RequestBuilder loginRequest(String username, String password) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "tenantCode", TENANT_CODE, "username", username, "password", password));
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(loginRequest(username, password))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(json).path("data").path("tokenValue").asText();
    }

    /** 造一个用户并授予角色；租户 1 与角色都是建表脚本的种子数据。 */
    private SysUser givenUser(String username, int userType, long roleId) {
        SysUser user = new SysUser();
        user.setTenantId(1L);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName("测试用户");
        user.setUserType(userType);
        user.setStatus(1);
        assertThat(sysUserMapper.insert(user)).isEqualTo(1);

        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(roleId);
        sysUserRoleMapper.insert(link);
        return user;
    }
}
