package com.bluemalic.repair;

import com.bluemalic.repair.common.RateLimiter;
import com.bluemalic.repair.config.RateLimitRule;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 限流（ADR-009）。两套机制共用同一个 Redis 原语（{@code RateLimiter}），但计数维度不同，
 * 所以分开验证：
 *
 * <p><b>报修码查询 / 扫码到场（拦截器，按登录用户计）</b>重点验证三件事，都是"写错了也照样能跑"的那种：
 *
 * <ol>
 *   <li><b>阈值边界</b>：第 N 次放行、第 N+1 次拦（10004），且业务失败仍是 HTTP 200 + 业务码</li>
 *   <li><b>计数键不含路径变量</b>：换一个报修码继续猜，不能重置计数 —— 若把 requestURI 当键，
 *       每次猜码都落在独立的计数器上，限流等于不存在</li>
 *   <li><b>限流在业务逻辑之前</b>：到场接口拿一个不存在的工单 ID 也能把计数打满，
 *       说明它是在进入 Service 之前拦下的（也顺带证明 20001 优先于权限问题，不是被 403 挡的）</li>
 * </ol>
 *
 * <p><b>登录（业务层，按租户 + 账号计）</b>验证的是另一组性质：按账号分桶（别人被刷不影响我）、
 * 以及<b>用对密码也照样被拦</b>——计数发生在验密之前，否则爆破者只要用错密码就会被"只统计失败"
 * 的实现放过额度。
 *
 * <p>阈值从 {@link RateLimitRule} 读，不硬写 60：测试环境若用环境变量调过阈值，这里跟着走。
 *
 * <p>Redis 里的计数不随测试事务回滚，所以每个用例后显式清理。计数本身有 60 秒 TTL，
 * 不清也会自己消失，但"靠 TTL 过期"会让连跑多轮的行为依赖时间，不如清干净。
 */
@IntegrationTest
class RateLimitTest {

    private static final String PASSWORD = "Test@123456";
    private static final String VALID_CODE = "482913";
    /** 同一个租户下的另一个合法码（3号楼 3-412），用于验证"换码不换桶"。 */
    private static final String OTHER_VALID_CODE = "306718";

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

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RateLimitRule rule;

    @AfterEach
    void clearCounters() {
        Set<String> keys = redis.keys(RateLimiter.KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void blocksTheRequestAfterThreshold() throws Exception {
        String student = givenToken("test-rate-limit-student", 1, 1L);

        for (int i = 1; i <= rule.getMaxRequests(); i++) {
            byCode(student, VALID_CODE).andExpect(jsonPath("$.code").value(0));
        }

        // 第 N+1 次：拦下，但仍是 HTTP 200 + 业务码（docs/03 §2.3 的约定，前端只读 body.code）
        byCode(student, VALID_CODE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10004));
    }

    @Test
    void counterIsPerUser() throws Exception {
        String exhausted = givenToken("test-rate-limit-a", 1, 1L);
        String other = givenToken("test-rate-limit-b", 1, 1L);

        exhaust(exhausted);

        byCode(exhausted, VALID_CODE).andExpect(jsonPath("$.code").value(10004));
        // 同租户的另一个学生不受影响：限流按登录用户计，不能因为一个人被拦就影响别人
        byCode(other, VALID_CODE).andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void switchingRepairCodeDoesNotResetCounter() throws Exception {
        String student = givenToken("test-rate-limit-switch", 1, 1L);

        // 先确认另一个码本身是合法的（否则待会儿的 10004 可能只是 20006 被掩盖）
        byCode(student, OTHER_VALID_CODE).andExpect(jsonPath("$.code").value(0));

        // 换回第一个码，把额度用光（含上面那次，合计恰好 maxRequests 次）
        for (int i = 1; i < rule.getMaxRequests(); i++) {
            byCode(student, VALID_CODE).andExpect(jsonPath("$.code").value(0));
        }

        // 额度用尽后再换码继续猜：仍是 10004 —— 两个码共用同一个计数桶
        byCode(student, OTHER_VALID_CODE).andExpect(jsonPath("$.code").value(10004));
    }

    @Test
    void arriveEndpointIsLimitedToo() throws Exception {
        String worker = givenToken("test-rate-limit-worker", 2, 2L);

        // 工单不存在（20001）：能拿到这个码说明权限校验已通过，业务也已执行到查库这一步，
        // 而计数发生在更早的拦截器里 —— 所以"业务失败"同样计入限流
        for (int i = 1; i <= rule.getMaxRequests(); i++) {
            arrive(worker, "999999999", VALID_CODE).andExpect(jsonPath("$.code").value(20001));
        }

        arrive(worker, "999999999", VALID_CODE).andExpect(jsonPath("$.code").value(10004));
        // 到场接口与按码查询是两个独立的桶，互不牵连
        byCode(worker, VALID_CODE).andExpect(jsonPath("$.code").value(0));
    }

    // ==================== 登录：按租户 + 账号计数 ====================

    @Test
    void loginIsLimitedPerAccount() throws Exception {
        // 用不存在的账号：限流在验密之前就计数，所以不需要造用户（也就没有 BCrypt 的开销）
        for (int i = 1; i <= rule.getLoginMaxRequests(); i++) {
            login("test-rate-limit-ghost", "whatever-password")
                    .andExpect(jsonPath("$.code").value(30001));
        }

        login("test-rate-limit-ghost", "whatever-password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10004));
    }

    @Test
    void loginCounterIsPerAccount() throws Exception {
        exhaustLogin("test-rate-limit-victim");

        // 同租户下的另一个账号不受影响：不能因为有人刷某个学号，别人就登不上
        login("test-rate-limit-other", "whatever-password")
                .andExpect(jsonPath("$.code").value(30001));
    }

    @Test
    void correctPasswordIsAlsoBlockedOnceExhausted() throws Exception {
        givenUser("test-rate-limit-real", 1, 1L);
        exhaustLogin("test-rate-limit-real");

        // 第 N+1 次改成用**正确的**密码：照样 10004。
        // 这条是在守"计数发生在验密之前"——若实现成"只统计失败次数"，爆破者反而能无限试。
        login("test-rate-limit-real", PASSWORD)
                .andExpect(jsonPath("$.code").value(10004));
    }

    // ==================== 工具 ====================

    /** 把某个账号的登录额度打满（用错密码，最后一次仍然放行）。 */
    private void exhaustLogin(String username) throws Exception {
        for (int i = 1; i <= rule.getLoginMaxRequests(); i++) {
            login(username, "whatever-password").andExpect(jsonPath("$.code").value(30001));
        }
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "tenantCode", "gdou", "username", username, "password", password))));
    }

    /** 把某个用户的计数打到阈值上限（本次请求仍然放行）。 */
    private void exhaust(String token) throws Exception {
        for (int i = 1; i <= rule.getMaxRequests(); i++) {
            byCode(token, VALID_CODE).andExpect(jsonPath("$.code").value(0));
        }
    }

    private ResultActions byCode(String token, String code) throws Exception {
        return mockMvc.perform(get("/api/tickets/by-code/" + code)
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions arrive(String token, String ticketId, String code) throws Exception {
        return mockMvc.perform(post("/api/worker/tickets/" + ticketId + "/arrive")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"repairCode\":\"" + code + "\"}"));
    }

    /** 造用户 + 角色并登录拿 token（与其它测试同一套写法：租户 1 = 种子数据 gdou）。 */
    private String givenToken(String username, int userType, long roleId) throws Exception {
        givenUser(username, userType, roleId);

        MvcResult result = login(username, PASSWORD)
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("tokenValue").asText();
    }

    /** 只造用户和角色关联，不登录（需要自己控制登录次数时用）。 */
    private void givenUser(String username, int userType, long roleId) {
        SysUser user = new SysUser();
        user.setTenantId(1L);
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
    }
}
