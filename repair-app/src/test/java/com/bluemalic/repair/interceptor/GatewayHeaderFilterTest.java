package com.bluemalic.repair.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务端身份头校验（阶段 3.2 · {@code GatewayHeaderFilter}）。
 *
 * <p>这些用例是纯 Filter 单元测试，不起 Spring 上下文：验证的是契约（放行 / 10002），
 * 与登录态、租户、数据库都无关。签名算法必须与网关 {@code AuthGlobalFilter#sign} 一模一样，
 * 这里独立算一遍——如果两边哪天改得不一致，这个测试会红。
 */
class GatewayHeaderFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void off模式不看头也放行() throws Exception {
        GatewayHeaderFilter filter = filter("off", "");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/student/tickets");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("请求应当继续往下走").isNotNull();
    }

    @Test
    void verify模式下缺少身份头返回10002() throws Exception {
        GatewayHeaderFilter filter = filter("verify", "");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/tickets");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString()).contains("\"code\":10002");
    }

    @Test
    void verify模式白名单不要求身份头() throws Exception {
        GatewayHeaderFilter filter = filter("verify", "");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void 配了secret时签名错误会被拒绝() throws Exception {
        GatewayHeaderFilter filter = filter("verify", "s3cret");
        MockHttpServletRequest request = signedRequest("s3cret", Instant.now().getEpochSecond(), "wrong-sign");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString()).contains("\"code\":10002");
    }

    @Test
    void 签名正确时放行() throws Exception {
        GatewayHeaderFilter filter = filter("verify", "s3cret");
        String ts = String.valueOf(Instant.now().getEpochSecond());
        MockHttpServletRequest request = signedRequest("s3cret", Long.parseLong(ts),
                hmac("7||3|" + ts, "s3cret"));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void 签名过期会被拒绝() throws Exception {
        GatewayHeaderFilter filter = filter("verify", "s3cret");
        long old = Instant.now().getEpochSecond() - 120;
        MockHttpServletRequest request = signedRequest("s3cret", old, hmac("7||3|" + old, "s3cret"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString()).contains("\"code\":10002");
    }

    private GatewayHeaderFilter filter(String mode, String secret) {
        GatewayHeaderFilter filter = new GatewayHeaderFilter(objectMapper);
        ReflectionTestUtils.setField(filter, "headerMode", mode);
        ReflectionTestUtils.setField(filter, "hmacSecret", secret);
        return filter;
    }

    private static MockHttpServletRequest signedRequest(String secret, long ts, String sign) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/tickets");
        request.addHeader("X-User-Id", "7");
        request.addHeader("X-Tenant-Id", "");
        request.addHeader("X-User-Type", "3");
        request.addHeader("X-Gateway-Ts", String.valueOf(ts));
        request.addHeader("X-Gateway-Sign", sign);
        return request;
    }

    private static String hmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
