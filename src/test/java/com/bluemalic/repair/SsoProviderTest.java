package com.bluemalic.repair;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.entity.TenantSsoConfig;
import com.bluemalic.repair.sso.CasSsoProvider;
import com.bluemalic.repair.sso.OAuth2SsoProvider;
import com.bluemalic.repair.sso.SsoHttpClient;
import com.bluemalic.repair.sso.SsoIdentity;
import com.bluemalic.repair.sso.SsoProperties;
import com.bluemalic.repair.sso.SsoStateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 两个 SSO 协议适配器（阶段 7 · ADR-022）：**用真的 HTTP 验**，而不是 mock 掉 HTTP 客户端。
 *
 * <p>为什么值得起一个本地 HttpServer（JDK 自带，零依赖）：适配器最容易错的地方就是"协议细节"——
 * 表单字段名、PKCE 的 S256、Bearer 头、CAS 的 {@code service} 参数与 JSON/XML 两种返回。
 * 把 HTTP 打桩掉，这些恰恰验不到；而起一个真实端口只花几十毫秒。
 *
 * <p>它不测"登录"（那是 {@code SsoFlowTest} 的事），只测**怎么问 IdP 要身份**。
 */
class SsoProviderTest {

    private HttpServer server;
    private String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 假 IdP 的状态：由各用例设置，handler 读它决定怎么回。 */
    private volatile Map<String, String> userinfo;
    private volatile String casBody;
    private volatile int casStatus = 200;
    /** token 端点：期望的 PKCE challenge（来自授权 URL），对不上就回 400 invalid_grant */
    private final AtomicReference<String> expectedChallenge = new AtomicReference<>();

    @BeforeEach
    void startFakeIdp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            Map<String, String> form = parseForm(readBody(exchange));
            String challenge = expectedChallenge.get();
            boolean pkceOk = challenge == null
                    || challenge.equals(SsoStateStore.codeChallenge(form.get("code_verifier")));
            if (!pkceOk || !"the-code".equals(form.get("code"))
                    || !"client-1".equals(form.get("client_id"))
                    || !"secret-1".equals(form.get("client_secret"))) {
                respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
            respond(exchange, 200, "{\"access_token\":\"token-1\",\"token_type\":\"Bearer\"}");
        });
        server.createContext("/userinfo", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer token-1".equals(auth)) {
                respond(exchange, 401, "{}");
                return;
            }
            respond(exchange, 200, objectMapper.writeValueAsString(userinfo));
        });
        server.createContext("/cas/validate", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseForm(query);
            if (!"the-ticket".equals(params.get("ticket")) || !"http://front.test/cb".equals(params.get("service"))) {
                respond(exchange, 200, "{\"serviceResponse\":{\"authenticationFailure\":{\"code\":\"INVALID_TICKET\"}}}");
                return;
            }
            respond(exchange, casStatus, casBody);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopFakeIdp() {
        server.stop(0);
    }

    // ==================== OAuth2 ====================

    @Test
    void oauth2授权地址带state与PKCE的S256() {
        OAuth2SsoProvider provider = oauth2Provider();
        String url = provider.authorizationUrl(oauth2Config("username"), "state-1",
                "http://front.test/cb", SsoStateStore.codeChallenge("verifier-1"));

        assertThat(url).startsWith(baseUrl + "/authorize");
        assertThat(url).contains("response_type=code", "client_id=client-1", "state=state-1",
                "code_challenge_method=S256", "code_challenge=");
        // `:` 与 `/` 在 query 值里是合法字符（不编码也是对的）；真正必须编码的是 `&`——
        // 否则带了查询参数的回跳地址会被截断成两个参数
        assertThat(url).contains("redirect_uri=http://front.test/cb");
        String nested = provider.authorizationUrl(oauth2Config("username"), "state-1",
                "http://front.test/cb?a=1&b=2", SsoStateStore.codeChallenge("verifier-1"));
        assertThat(nested).contains("a%3D1%26b%3D2");
    }

    @Test
    void oauth2用code加PKCE换出身份_并按配置的属性取登录名() {
        expectedChallenge.set(SsoStateStore.codeChallenge("verifier-1"));
        userinfo = Map.of("sub", "u-1", "studentNo", "20260001", "name", "王小二");

        SsoIdentity identity = oauth2Provider().authenticate(
                oauth2Config("studentNo"), "the-code", "http://front.test/cb", "verifier-1");

        assertThat(identity.username()).isEqualTo("20260001");
        assertThat(identity.subject()).isEqualTo("u-1");
        assertThat(identity.realName()).isEqualTo("王小二");
    }

    @Test
    void oauth2的PKCE对不上时按统一认证失败处理() {
        expectedChallenge.set(SsoStateStore.codeChallenge("verifier-1"));
        userinfo = Map.of("sub", "u-1", "username", "20260001");

        assertThatThrownBy(() -> oauth2Provider().authenticate(
                oauth2Config("username"), "the-code", "http://front.test/cb", "wrong-verifier"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("统一认证失败");
    }

    @Test
    void oauth2的用户信息缺少登录名字段时给出明确报错() {
        expectedChallenge.set(SsoStateStore.codeChallenge("verifier-1"));
        userinfo = Map.of("sub", "u-1", "name", "没有登录名字段");

        assertThatThrownBy(() -> oauth2Provider().authenticate(
                oauth2Config("username"), "the-code", "http://front.test/cb", "verifier-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有返回登录名");
    }

    // ==================== CAS ====================

    @Test
    void cas授权地址带service() {
        String url = new CasSsoProvider(httpClient(), objectMapper)
                .authorizationUrl(casConfig(), "state-1", "http://front.test/cb", "ignored");

        assertThat(url).startsWith(baseUrl + "/cas/login?service=");
        // state 嵌在 service 里（CAS 只回 ticket，不会带 state 回来）
        String service = URLDecoder.decode(
                url.substring((baseUrl + "/cas/login?service=").length()), StandardCharsets.UTF_8);
        assertThat(service).isEqualTo("http://front.test/cb?state=state-1");
    }

    @Test
    void cas用ticket换用户名_支持JSON() {
        casBody = "{\"serviceResponse\":{\"authenticationSuccess\":{\"user\":\"20260001\"}}}";

        SsoIdentity identity = new CasSsoProvider(httpClient(), objectMapper)
                .authenticate(casConfig(), "the-ticket", "http://front.test/cb", "");

        assertThat(identity.username()).isEqualTo("20260001");
    }

    @Test
    void cas的JSON解析不了时退回XML() {
        casBody = "<cas:serviceResponse xmlns:cas='http://www.yale.edu/tp/cas'>"
                + "<cas:authenticationSuccess><cas:user>worker01</cas:user></cas:authenticationSuccess>"
                + "</cas:serviceResponse>";

        SsoIdentity identity = new CasSsoProvider(httpClient(), objectMapper)
                .authenticate(casConfig(), "the-ticket", "http://front.test/cb", "");

        assertThat(identity.username()).isEqualTo("worker01");
    }

    @Test
    void cas校验失败时按统一认证失败处理() {
        casBody = "{\"serviceResponse\":{\"authenticationFailure\":{\"code\":\"INVALID_TICKET\"}}}";

        assertThatThrownBy(() -> new CasSsoProvider(httpClient(), objectMapper)
                .authenticate(casConfig(), "the-ticket", "http://front.test/cb", ""))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("统一认证失败");
    }

    // ==================== 工具 ====================

    private OAuth2SsoProvider oauth2Provider() {
        return new OAuth2SsoProvider(httpClient(), objectMapper);
    }

    private SsoHttpClient httpClient() {
        return new SsoHttpClient(new SsoProperties(false, 300, 120, 1000, 5));
    }

    private TenantSsoConfig oauth2Config(String usernameAttribute) {
        TenantSsoConfig config = new TenantSsoConfig();
        config.setTenantId(1L);
        config.setProtocol("OAUTH2");
        config.setAuthorizeUrl(baseUrl + "/authorize");
        config.setTokenUrl(baseUrl + "/token");
        config.setUserinfoUrl(baseUrl + "/userinfo");
        config.setClientId("client-1");
        config.setClientSecret("secret-1");
        config.setUsernameAttribute(usernameAttribute);
        return config;
    }

    private TenantSsoConfig casConfig() {
        TenantSsoConfig config = new TenantSsoConfig();
        config.setTenantId(1L);
        config.setProtocol("CAS");
        config.setCasLoginUrl(baseUrl + "/cas/login");
        config.setCasValidateUrl(baseUrl + "/cas/validate");
        return config;
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Map<String, String> parseForm(String raw) {
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
}
