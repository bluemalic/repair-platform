package com.bluemalic.repair.gateway.filter;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 把登录态注入成下游可用的身份头（阶段 2 · ADR-024）。
 *
 * <p>三个头的键与单体 Session 里的键一致（{@code tenantId} / {@code userType} 由
 * {@code CurrentTenantServiceImpl} 与 {@code AuthServiceImpl} 写入，这里只读）：
 * <ul>
 *   <li>{@code X-User-Id}：Sa-Token 的 loginId（= sys_user.id）</li>
 *   <li>{@code X-Tenant-Id}：Session 里的租户 ID</li>
 *   <li>{@code X-User-Type}：1 学生 / 2 维修工 / 3 后勤 / 4 平台运营</li>
 * </ul>
 *
 * <p><b>先删再写</b>：客户端自己带的同名头必须是不可信的，否则任何人都能伪造身份。
 * 网关**覆盖**这三个头，而不是追加。
 *
 * <p>只对 {@code /api/**} 的白名单之外请求注入；登录前（白名单）没有登录态，原样放行，
 * 由下游登录接口自己判断。
 */
@Slf4j
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    static final String HEADER_USER_ID = "X-User-Id";
    static final String HEADER_TENANT_ID = "X-Tenant-Id";
    static final String HEADER_USER_TYPE = "X-User-Type";
    static final String HEADER_GATEWAY_TS = "X-Gateway-Ts";
    static final String HEADER_GATEWAY_SIGN = "X-Gateway-Sign";

    /**
     * 服务内验签的共享密钥。为空表示不签名（本地开发 / 网关与服务直连同一网络时的默认）。
     * 设了之后每个请求多两个头，服务端（common 的 {@code GatewayHeaderFilter}）按同样算法校验。
     */
    @Value("${repair.gateway.hmac-secret:}")
    private String hmacSecret;

    /** 与 SaTokenGatewayConfig 的白名单一致：这些路径没有登录态，不注入身份头。 */
    static boolean isWhiteListed(String path) {
        return "/api/auth/login".equals(path)
                || path.startsWith("/api/auth/sso/")
                || path.startsWith("/api/mock-idp/");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/") || isWhiteListed(path)) {
            return chain.filter(exchange);
        }

        // 不调用 StpUtil.getLoginIdDefaultNull()：GlobalFilter 可能跑在另一个 Netty 线程，
        // Sa-Token 的 ThreadLocal 上下文在那里是空的（实测抛 SaTokenContext 异常 → 500）。
        // 改成按 token 直查 DAO（getLoginIdByToken / getSessionByLoginId 都不依赖请求上下文），
        // 登录校验本身仍由 SaReactorFilter 负责。
        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            // 正常走不到这里：SaReactorFilter 已拦截未登录。保留兜底，避免静默伪造头
            return chain.filter(exchange);
        }
        Object loginId = StpUtil.getStpLogic().getLoginIdByToken(authorization.substring("Bearer ".length()).trim());
        if (loginId == null) {
            return chain.filter(exchange);
        }
        SaSession session = StpUtil.getSessionByLoginId(loginId, false);
        Object tenantId = session == null ? null : session.get("tenantId");
        Object userType = session == null ? null : session.get("userType");

        String userIdHeader = String.valueOf(loginId);
        String tenantIdHeader = tenantId == null ? "" : String.valueOf(tenantId);
        String userTypeHeader = userType == null ? "" : String.valueOf(userType);

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    // 客户端伪造的同名头先清掉，再由网关写入可信值
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_TENANT_ID);
                    headers.remove(HEADER_USER_TYPE);
                    headers.remove(HEADER_GATEWAY_TS);
                    headers.remove(HEADER_GATEWAY_SIGN);
                })
                .header(HEADER_USER_ID, userIdHeader)
                .header(HEADER_TENANT_ID, tenantIdHeader)
                .header(HEADER_USER_TYPE, userTypeHeader)
                .build();

        if (hmacSecret != null && !hmacSecret.isBlank()) {
            String ts = String.valueOf(Instant.now().getEpochSecond());
            String payload = userIdHeader + "|" + tenantIdHeader + "|" + userTypeHeader + "|" + ts;
            request = request.mutate()
                    .header(HEADER_GATEWAY_TS, ts)
                    .header(HEADER_GATEWAY_SIGN, sign(payload, hmacSecret))
                    .build();
        }
        return chain.filter(exchange.mutate().request(request).build());
    }

    /** HMAC-SHA256 十六进制小写；算法与服务端 {@code GatewayHeaderFilter} 必须逐字节一致。 */
    static String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // 算法在本 JDK 上必然存在；走到这里说明运行环境被裁剪过，宁可 500 也不要发一个假签名
            throw new IllegalStateException("网关签名失败", e);
        }
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
