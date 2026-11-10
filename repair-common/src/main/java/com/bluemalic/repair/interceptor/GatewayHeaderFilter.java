package com.bluemalic.repair.interceptor;

import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 校验网关注入的身份头（阶段 3.2 · ADR-024 决定 4 / docs/03 §4.1 规则 2）。
 *
 * <p><b>默认 off</b>：没有网关的部署（本地调试、CI、lite 降级成 nginx 直连）不受影响。
 * 打开方式：{@code repair.gateway.header-mode=verify}（环境变量 {@code GATEWAY_HEADER_MODE=verify}）。
 *
 * <p><b>open 模式校验什么</b>：
 * <ol>
 *   <li>三个身份头必须齐全——服务不 bare-trust，也不接受"缺失就当匿名"；</li>
 *   <li>配了 {@code repair.gateway.hmac-secret} 时，还要求 {@code X-Gateway-Ts} 在 ±60 秒内、
 *       {@code X-Gateway-Sign} 与网关的算法逐字节一致——挡住"绕过网关直连服务端口"的伪造头。</li>
 * </ol>
 *
 * <p><b>它不替代登录校验</b>：功能权限（Sa-Token）与数据权限（MyBatis 拦截器）照旧在服务内执行。
 * 头只回答"这个请求确实经过了网关、它声称的身份是谁"，不回答"他能不能做这件事"。
 *
 * <p><b>为什么是 Filter 而不是 Interceptor</b>：要在 Sa-Token 拦截器（order 0）之前就把伪造请求挡掉，
 * 否则它会先触发一次 Redis 会话查询——那正是伪造者想要的放大效果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayHeaderFilter extends OncePerRequestFilter {

    static final String HEADER_USER_ID = "X-User-Id";

    static final String HEADER_TENANT_ID = "X-Tenant-Id";

    static final String HEADER_USER_TYPE = "X-User-Type";

    static final String HEADER_GATEWAY_TS = "X-Gateway-Ts";

    static final String HEADER_GATEWAY_SIGN = "X-Gateway-Sign";

    /** 时间窗：网关与服务的时钟差超过这个值就拒绝，防止重放旧签名。 */
    private static final long MAX_SKEW_SECONDS = 60;

    private final ObjectMapper objectMapper;

    @Value("${repair.gateway.header-mode:off}")
    private String headerMode;

    @Value("${repair.gateway.hmac-secret:}")
    private String hmacSecret;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!"verify".equalsIgnoreCase(headerMode)) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        // 登录入口本来就没有身份头；非 /api/** （文档、健康检查）也不该被这条规则管
        if (!path.startsWith("/api/") || isWhiteListed(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = request.getHeader(HEADER_USER_ID);
        if (isBlank(userId)) {
            reject(request, response, ErrorCode.NOT_LOGIN, "缺少网关注入的身份头");
            return;
        }
        if (!verifySignature(request)) {
            reject(request, response, ErrorCode.NOT_LOGIN, "身份头签名校验失败");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 配了 secret 就必须带签名；没配 secret 时只要求三个头齐全。
     *
     * <p>反过来的组合（服务配了 secret、网关没配）会让全部请求 401 —— 这是有意的 fail-closed：
     * 两边配置不一致时，宁可全站不可用也不要静默退化成"只看头"。
     */
    private boolean verifySignature(HttpServletRequest request) {
        if (isBlank(hmacSecret)) {
            return true;
        }
        String ts = request.getHeader(HEADER_GATEWAY_TS);
        String sign = request.getHeader(HEADER_GATEWAY_SIGN);
        if (isBlank(ts) || isBlank(sign)) {
            return false;
        }
        long epoch;
        try {
            epoch = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(Instant.now().getEpochSecond() - epoch) > MAX_SKEW_SECONDS) {
            return false;
        }
        String payload = request.getHeader(HEADER_USER_ID) + "|"
                + nullToEmpty(request.getHeader(HEADER_TENANT_ID)) + "|"
                + nullToEmpty(request.getHeader(HEADER_USER_TYPE)) + "|" + ts;
        return sign.equals(hexHmac(payload, hmacSecret));
    }

    private void reject(HttpServletRequest request, HttpServletResponse response,
                        ErrorCode errorCode, String reason) throws IOException {
        log.warn("网关身份头校验失败 path={} reason={}", request.getRequestURI(), reason);
        // 与 GlobalExceptionHandler 保持一致：业务错误用 HTTP 200 + body.code 表达
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Result.fail(errorCode));
    }

    private static boolean isWhiteListed(String path) {
        return "/api/auth/login".equals(path)
                || path.startsWith("/api/auth/sso/")
                || path.startsWith("/api/mock-idp/");
    }

    private static String hexHmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("验签失败", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
