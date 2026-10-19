package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.AuditAction;
import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.entity.AuditLog;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.mapper.AuditLogMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.sso.SsoStateStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一认证的**完整登录链路**（阶段 7 · ADR-022）：
 * 授权 → （在 IdP 那边登录）→ 回调 → 一次性登录码 → 换 token → 用 token 访问接口。
 *
 * <p>IdP 是测试里起的一个真 HTTP 服务（JDK 自带），应用会真的去它那里换 token、取用户信息——
 * 所以这条链路里除了"浏览器点按钮"之外没有替身。重点盯五件事：
 *
 * <ol>
 *   <li><b>能登录</b>：换出来的 token 能调 {@code /api/auth/me}，角色/权限与账号密码登录一致；</li>
 *   <li><b>登录码一次性</b>：同一个 {@code ssoCode} 换第二次直接失败；state 同理；</li>
 *   <li><b>平台不自动建号</b>：IdP 认了、平台里没这个学号 → 带回明确的"联系后勤"提示；</li>
 *   <li><b>防开放重定向</b>：只回配置里的地址（请求只能选 ADMIN / H5）；</li>
 *   <li><b>写审计</b>：SSO 登录与账号密码登录一样记 LOGIN_SUCCESS。</li>
 * </ol>
 */
@IntegrationTest
class SsoFlowTest {

    private static final long TENANT = 1L;
    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;
    private static final long ROLE_PLATFORM = 4L;

    private static HttpServer idp;
    private static String idpBase;

    /** 假 IdP 状态：当前要返回的登录名；授权码 → PKCE challenge */
    private static volatile String ssoUsername;
    /** CAS 授权时用的 service（含 state），validate 必须一字不差 */
    private static volatile String ssoCasService;
    private static final Map<String, String> codes = new ConcurrentHashMap<>();

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
    private PasswordEncoder passwordEncoder;

    @BeforeAll
    static void startIdp() throws IOException {
        idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        idp.createContext("/token", exchange -> {
            Map<String, String> form = parseForm(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            String challenge = codes.remove(form.get("code"));
            if (challenge == null || !challenge.equals(SsoStateStore.codeChallenge(form.get("code_verifier")))) {
                respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
            respond(exchange, 200, "{\"access_token\":\"flow-token\",\"token_type\":\"Bearer\"}");
        });
        idp.createContext("/userinfo", exchange -> {
            if (!"Bearer flow-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 401, "{}");
                return;
            }
            respond(exchange, 200, "{\"sub\":\"idp-" + ssoUsername + "\",\"username\":\"" + ssoUsername
                    + "\",\"name\":\"统一认证用户\"}");
        });
        idp.createContext("/cas/validate", exchange -> {
            Map<String, String> query = parseForm(exchange.getRequestURI().getQuery());
            // 真实 CAS 会校验 service 与授权时完全一致（ticket 绑定在它上面）；
            // 假 IdP 也这么做，否则这个差异要到线上才暴露
            boolean serviceOk = ssoCasService != null && ssoCasService.equals(query.get("service"));
            if (!"ST-flow".equals(query.get("ticket")) || !serviceOk) {
                respond(exchange, 200, "{\"serviceResponse\":{\"authenticationFailure\":{\"code\":\"INVALID_TICKET\"}}}");
                return;
            }
            respond(exchange, 200, "{\"serviceResponse\":{\"authenticationSuccess\":{\"user\":\"" + ssoUsername + "\"}}}");
        });
        idp.start();
        idpBase = "http://127.0.0.1:" + idp.getAddress().getPort();
    }

    @AfterAll
    static void stopIdp() {
        idp.stop(0);
    }

    @BeforeEach
    void reset() throws Exception {
        codes.clear();
        ssoUsername = "test-sso-student";
        // 账号由学校后台先建好（SSO 不会替你建号）：这里直接插一个启用中的学生
        SysUser user = new SysUser();
        user.setTenantId(TENANT);
        user.setUsername(ssoUsername);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName("统一认证学生");
        user.setUserType(UserType.STUDENT.getCode());
        user.setStatus(1);
        user.setMustChangePassword(0);
        sysUserMapper.insert(user);
        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(ROLE_STUDENT);
        sysUserRoleMapper.insert(link);
    }

    // ==================== OAuth2 ====================

    @Test
    void oauth2走完整链路_换出的token能用_且登录码一次性() throws Exception {
        configureOAuth2();

        // ① 授权：拿回 IdP 地址，并从里面取出 state 与 PKCE challenge
        String authorizeUrl = redirectOf(mockMvc.perform(get("/api/auth/sso/authorize")
                        .param("tenantCode", "gdou").param("target", "H5"))
                .andExpect(status().isFound()).andReturn());
        Map<String, String> authorizeParams = parseForm(URI.create(authorizeUrl).getQuery());
        assertThat(authorizeUrl).startsWith(idpBase + "/authorize");
        assertThat(authorizeParams).containsKeys("state", "code_challenge", "redirect_uri");
        assertThat(authorizeParams.get("code_challenge_method")).isEqualTo("S256");

        // ② 用户在 IdP 那边登录：IdP 发一个绑定了该 challenge 的授权码
        String code = "code-1";
        codes.put(code, authorizeParams.get("code_challenge"));

        // ③ 回调：应用去 IdP 换 token / 取用户信息，然后 302 回前端并带一次性登录码
        String frontRedirect = redirectOf(mockMvc.perform(get("/api/auth/sso/callback")
                        .param("code", code).param("state", authorizeParams.get("state")))
                .andExpect(status().isFound()).andReturn());
        assertThat(frontRedirect).startsWith("http://front.test/h5");
        String ssoCode = parseForm(URI.create(frontRedirect).getQuery()).get("ssoCode");
        assertThat(ssoCode).isNotBlank();

        // ④ 换 token：这一步才真正完成登录
        MvcResult exchange = mockMvc.perform(post("/api/auth/sso/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", ssoCode))))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value(ssoUsername))
                .andReturn();
        String token = data(exchange).path("tokenValue").asText();

        // ⑤ token 能用，且角色来自这所学校自己的账号
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.roles[0]").value("STUDENT"));

        // ⑥ 登录码一次性：同一个码换第二次失败
        mockMvc.perform(post("/api/auth/sso/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", ssoCode))))
                .andExpect(jsonPath("$.code").value(10001));

        // ⑦ 与账号密码登录同一条审计：LOGIN_SUCCESS
        assertThat(auditLogMapper.selectCount(Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getTenantId, TENANT)
                .eq(AuditLog::getAction, AuditAction.LOGIN_SUCCESS)
                .eq(AuditLog::getOperatorId, userIdOf(ssoUsername)))).isEqualTo(1);
    }

    @Test
    void 回调的state用过一次就失效() throws Exception {
        configureOAuth2();
        String authorizeUrl = redirectOf(mockMvc.perform(get("/api/auth/sso/authorize")
                        .param("tenantCode", "gdou").param("target", "H5"))
                .andExpect(status().isFound()).andReturn());
        String state = parseForm(URI.create(authorizeUrl).getQuery()).get("state");
        codes.put("code-2", parseForm(URI.create(authorizeUrl).getQuery()).get("code_challenge"));

        mockMvc.perform(get("/api/auth/sso/callback").param("code", "code-2").param("state", state))
                .andExpect(status().isFound());
        // 同一个 state 再来一次：直接拒绝（不是"再登录一次"，而是"这个请求已经用过了"）
        mockMvc.perform(get("/api/auth/sso/callback").param("code", "code-2").param("state", state))
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void 平台没有这个账号时带回联系后勤的提示_不会自动建号() throws Exception {
        configureOAuth2();
        ssoUsername = "20269999";
        long before = sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery());

        String authorizeUrl = redirectOf(mockMvc.perform(get("/api/auth/sso/authorize")
                        .param("tenantCode", "gdou").param("target", "H5"))
                .andExpect(status().isFound()).andReturn());
        Map<String, String> params = parseForm(URI.create(authorizeUrl).getQuery());
        codes.put("code-3", params.get("code_challenge"));

        String redirect = redirectOf(mockMvc.perform(get("/api/auth/sso/callback")
                        .param("code", "code-3").param("state", params.get("state")))
                .andExpect(status().isFound()).andReturn());
        Map<String, String> result = parseForm(URI.create(redirect).getQuery());
        assertThat(result.get("ssoError")).isEqualTo("30001");
        assertThat(result.get("ssoMessage")).contains("联系学校后勤");
        assertThat(sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery())).as("不会自动建号").isEqualTo(before);
    }

    // ==================== CAS ====================

    @Test
    void cas走完整链路() throws Exception {
        configureCas();
        String authorizeUrl = redirectOf(mockMvc.perform(get("/api/auth/sso/authorize")
                        .param("tenantCode", "gdou").param("target", "H5"))
                .andExpect(status().isFound()).andReturn());
        assertThat(authorizeUrl).startsWith(idpBase + "/cas/login");
        // CAS 不会把 state 带回来：它嵌在 service 的查询串里（回调时由 CAS 追加 &ticket=...）
        String service = parseForm(URI.create(authorizeUrl).getQuery()).get("service");
        ssoCasService = service;
        String state = parseForm(URI.create(service).getQuery()).get("state");
        assertThat(state).isNotBlank();

        String redirect = redirectOf(mockMvc.perform(get("/api/auth/sso/callback")
                        .param("ticket", "ST-flow").param("state", state))
                .andExpect(status().isFound()).andReturn());
        String ssoCode = parseForm(URI.create(redirect).getQuery()).get("ssoCode");
        assertThat(ssoCode).isNotBlank();

        mockMvc.perform(post("/api/auth/sso/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", ssoCode))))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value(ssoUsername));
    }

    // ==================== 配置与入口 ====================

    @Test
    void 未开通时登录页拿到的答案是未启用_发起授权会被拒() throws Exception {
        // 没配置过的学校：available 回 enabled=false，而不是报错（登录页据此不显示入口）
        mockMvc.perform(get("/api/auth/sso/available").param("tenantCode", "gdou"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(false));

        mockMvc.perform(get("/api/auth/sso/authorize").param("tenantCode", "gdou").param("target", "H5"))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("这所学校还没有开通统一身份认证登录"));
    }

    @Test
    void 平台配置接口不回显密钥_缺必填项会被拒() throws Exception {
        String platform = givenPlatformToken("test-sso-platform");

        // 缺 OAuth2 的必填项
        mockMvc.perform(put("/api/platform/tenants/" + TENANT + "/sso")
                        .header("Authorization", "Bearer " + platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", 1, "protocol", "OAUTH2"))))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("还缺")));

        // 配好之后读回来：密钥只回"是否已配置"
        configureOAuth2();
        JsonNode config = objectMapper.readTree(mockMvc.perform(get("/api/platform/tenants/" + TENANT + "/sso")
                        .header("Authorization", "Bearer " + platform))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertThat(config.path("clientSecretConfigured").asBoolean()).isTrue();
        assertThat(config.has("clientSecret")).as("密钥绝不出现在出参里").isFalse();
        assertThat(config.path("protocol").asText()).isEqualTo("OAUTH2");
    }

    // ==================== 工具 ====================

    private void configureOAuth2() throws Exception {
        String platform = givenPlatformToken("test-sso-platform");
        Map<String, Object> body = new HashMap<>();
        body.put("enabled", 1);
        body.put("protocol", "OAUTH2");
        body.put("authorizeUrl", idpBase + "/authorize");
        body.put("tokenUrl", idpBase + "/token");
        body.put("userinfoUrl", idpBase + "/userinfo");
        body.put("clientId", "client-1");
        body.put("clientSecret", "secret-1");
        body.put("usernameAttribute", "username");
        body.put("adminRedirectUrl", "http://front.test/admin");
        body.put("h5RedirectUrl", "http://front.test/h5");
        mockMvc.perform(put("/api/platform/tenants/" + TENANT + "/sso")
                        .header("Authorization", "Bearer " + platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value(0));
    }

    private void configureCas() throws Exception {
        String platform = givenPlatformToken("test-sso-platform");
        Map<String, Object> body = new HashMap<>();
        body.put("enabled", 1);
        body.put("protocol", "CAS");
        body.put("casLoginUrl", idpBase + "/cas/login");
        body.put("casValidateUrl", idpBase + "/cas/validate");
        body.put("adminRedirectUrl", "http://front.test/admin");
        body.put("h5RedirectUrl", "http://front.test/h5");
        mockMvc.perform(put("/api/platform/tenants/" + TENANT + "/sso")
                        .header("Authorization", "Bearer " + platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value(0));
    }

    private String givenPlatformToken(String username) throws Exception {
        SysUser existing = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, 0L).eq(SysUser::getUsername, username));
        if (existing != null) {
            return login(username);
        }
        SysUser user = new SysUser();
        user.setTenantId(0L);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName(username);
        user.setUserType(UserType.PLATFORM.getCode());
        user.setStatus(1);
        user.setMustChangePassword(0);
        sysUserMapper.insert(user);
        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(ROLE_PLATFORM);
        sysUserRoleMapper.insert(link);
        return login(username);
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "platform", "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return data(result).path("tokenValue").asText();
    }

    private long userIdOf(String username) {
        return sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, TENANT).eq(SysUser::getUsername, username)).getId();
    }

    private String redirectOf(MvcResult result) {
        return result.getResponse().getHeader("Location");
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }

    private static Map<String, String> parseForm(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                result.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
