package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.AuditAction;
import com.bluemalic.repair.entity.AuditLog;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.mapper.AuditLogMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 登录事件入审计（`docs/01` §4.4）。
 *
 * <p>这一类的规则与其它审计记录**不一样**，所以测试盯的也正是那些不一样的地方：
 * <ul>
 *   <li><b>成功按「账号 + 天 + 来源 IP」去重</b>：同一人同一天从同一处只留一条，**换 IP 要另记一条**
 *       （那是"账号被盗"最该看见的信号）；失败不去重——"有人在试这个账号"是靠次数看出来的</li>
 *   <li><b>失败原因写清、接口响应不区分</b>：审计里是"账号不存在"/"口令错误"，接口一律回 30001</li>
 *   <li><b>默认不在列表里出现</b>：要勾「含登录事件」或按动作筛才看得到</li>
 *   <li><b>绝不记口令</b>：detail 里只有原因，没有他试的是什么</li>
 * </ul>
 *
 * <p><b>为什么 @BeforeEach 要清 Redis</b>：去重键在 Redis 里，而 Redis 不跟着事务回滚——
 * 不清的话，第二次跑这个类时"当天已经登录过"会把成功记录去重掉，用例会莫名其妙地红。
 */
@IntegrationTest
class LoginAuditTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;
    private static final long ROLE_ADMIN = 3L;
    /** 测试专用出口 IP：去重键里带 IP，写死一个固定值便于断言，也便于清理。 */
    private static final String IP_A = "203.0.113.10";
    private static final String IP_B = "203.0.113.20";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void clearLoginDedupeKeys() {
        Set<String> keys = stringRedisTemplate.keys("audit:login:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    @Test
    void successfulLoginIsAuditedOncePerDayAndIp() throws Exception {
        givenUser("test-la-student", 1, ROLE_STUDENT);

        login("test-la-student", PASSWORD, IP_A).andExpect(jsonPath("$.code").value(0));
        assertThat(loginsOf("test-la-student")).hasSize(1);

        // 同一天、同一个 IP 再登录：去重，不再记（H5 每次冷启动都要登录，全记会把审计表冲掉）
        login("test-la-student", PASSWORD, IP_A).andExpect(jsonPath("$.code").value(0));
        assertThat(loginsOf("test-la-student")).hasSize(1);

        // **换个 IP 就要另记一条**：同一天从两个地方登录，正是"账号被盗"最该看见的信号
        login("test-la-student", PASSWORD, IP_B).andExpect(jsonPath("$.code").value(0));
        List<AuditLog> rows = loginsOf("test-la-student");
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(AuditLog::getIp).containsExactlyInAnyOrder(IP_A, IP_B);
        assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.LOGIN_SUCCESS);
        assertThat(rows.get(0).getDetail()).isEqualTo("登录成功");
    }

    @Test
    void failedLoginsAreAuditedWithReasonAndWithoutPassword() throws Exception {
        givenUser("test-la-failed", 1, ROLE_STUDENT);

        // 口令错误：操作人是**那个账号**（能看出"是谁的账号被试了"）
        login("test-la-failed", "wrong-password", IP_A).andExpect(jsonPath("$.code").value(30001));
        AuditLog wrongPassword = onlyLoginOf("test-la-failed", AuditAction.LOGIN_FAILED);
        assertThat(wrongPassword.getDetail()).isEqualTo("口令错误");
        assertThat(wrongPassword.getOperatorId()).isEqualTo(userIdOf("test-la-failed"));

        // 账号不存在：操作人记 0（系统），姓名列写**尝试用的那个用户名**——这条记录的价值就在这里
        login("test-la-nobody", PASSWORD, IP_A).andExpect(jsonPath("$.code").value(30001));
        AuditLog unknown = onlyLoginOf("test-la-nobody", AuditAction.LOGIN_FAILED);
        assertThat(unknown.getDetail()).isEqualTo("账号不存在");
        assertThat(unknown.getOperatorId()).isZero();
        assertThat(unknown.getOperatorName()).isEqualTo("test-la-nobody");

        // **口令绝不落库**：整张表里不该出现任何一次尝试过的口令
        assertThat(auditLogMapper.selectList(Wrappers.<AuditLog>lambdaQuery())).allSatisfy(row -> {
            assertThat(row.getDetail()).doesNotContain("wrong-password").doesNotContain(PASSWORD);
            assertThat(row.getOperatorName()).doesNotContain(PASSWORD);
        });
        // 失败不去重：试两次就是两条（"有人在试这个账号"靠次数看出来）
        login("test-la-failed", "wrong-password-2", IP_B).andExpect(jsonPath("$.code").value(30001));
        assertThat(loginsOf("test-la-failed", AuditAction.LOGIN_FAILED)).hasSize(2);
    }

    @Test
    void disabledAccountLoginIsAuditedSeparately() throws Exception {
        long userId = givenUser("test-la-disabled", 1, ROLE_STUDENT);
        SysUser update = new SysUser();
        update.setId(userId);
        update.setStatus(0);
        sysUserMapper.updateById(update);

        // 停用账号：状态检查在口令校验之后，所以拿对的口令才能走到这一条
        login("test-la-disabled", PASSWORD, IP_A).andExpect(jsonPath("$.code").value(30002));
        AuditLog row = onlyLoginOf("test-la-disabled", AuditAction.LOGIN_DISABLED);
        assertThat(row.getDetail()).isEqualTo("账号已停用");
        assertThat(row.getOperatorId()).isEqualTo(userId);
    }

    @Test
    void unknownTenantLoginIsNotAuditedAtAll() throws Exception {
        // 租户都不存在：这条记录没有"谁的表"能放（审计是按租户查的），所以不记——留在服务器日志里
        login("test-la-nobody", PASSWORD, IP_A, "test-no-such-tenant")
                .andExpect(jsonPath("$.code").value(30003));

        assertThat(auditLogMapper.selectCount(Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getOperatorName, "test-la-nobody"))).isZero();
    }

    @Test
    void loginEventsAreHiddenFromTheListUnlessExplicitlyAskedFor() throws Exception {
        givenUser("test-la-list", 1, ROLE_STUDENT);
        login("test-la-list", PASSWORD, IP_A).andExpect(jsonPath("$.code").value(0));
        login("test-la-list", "wrong", IP_A).andExpect(jsonPath("$.code").value(30001));
        String admin = givenAdmin("test-la-admin");

        // 默认：列表只回答"谁改了东西"，登录记录不出现（它占绝大多数，会把主查询挤到后面）
        assertThat(listActions(admin, "/api/admin/audit-logs?pageNum=1&pageSize=50"))
                .doesNotContain(AuditAction.LOGIN_SUCCESS, AuditAction.LOGIN_FAILED);

        // 勾上「含登录事件」：看得到
        assertThat(listActions(admin, "/api/admin/audit-logs?pageNum=1&pageSize=50&includeLogin=true"))
                .contains(AuditAction.LOGIN_SUCCESS, AuditAction.LOGIN_FAILED);

        // 按动作筛：**即使不勾也看得到**——否则"筛了却查不到"会变成一个很难想明白的现象
        assertThat(listActions(admin, "/api/admin/audit-logs?pageNum=1&pageSize=50&action=LOGIN_FAILED"))
                .containsExactly(AuditAction.LOGIN_FAILED);
    }

    // ==================== 工具 ====================

    private List<AuditLog> loginsOf(String username) {
        return loginsOf(username, null);
    }

    private List<AuditLog> loginsOf(String username, String action) {
        return auditLogMapper.selectList(Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getOperatorName, username)
                .eq(action != null, AuditLog::getAction, action)
                .in(AuditLog::getAction, AuditAction.LOGIN_ACTIONS)
                .orderByAsc(AuditLog::getCreateTime));
    }

    private AuditLog onlyLoginOf(String username, String action) {
        List<AuditLog> rows = loginsOf(username, action);
        assertThat(rows).as("%s 的 %s 记录应恰好一条", username, action).hasSize(1);
        return rows.get(0);
    }

    private List<String> listActions(String token, String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asInt()).as("接口应返回成功：" + url + " → " + body).isZero();
        return java.util.stream.StreamSupport
                .stream(body.path("data").path("list").spliterator(), false)
                .map(node -> node.path("action").asText()).toList();
    }

    private org.springframework.test.web.servlet.ResultActions login(String username, String password, String ip)
            throws Exception {
        return login(username, password, ip, "gdou");
    }

    private org.springframework.test.web.servlet.ResultActions login(String username, String password, String ip,
                                                                    String tenantCode) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "tenantCode", tenantCode, "username", username, "password", password))));
    }

    private long givenUser(String username, int userType, long roleId) {
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
        return user.getId();
    }

    private String givenAdmin(String username) throws Exception {
        givenUser(username, 3, ROLE_ADMIN);
        MvcResult result = login(username, PASSWORD, IP_A).andExpect(jsonPath("$.code").value(0)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("tokenValue").asText();
    }

    private long userIdOf(String username) {
        return sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username)).getId();
    }
}
