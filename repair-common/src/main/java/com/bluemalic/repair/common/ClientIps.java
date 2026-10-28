package com.bluemalic.repair.common;

import org.springframework.util.StringUtils;

/**
 * 取客户端 IP（阶段 7 起抽出来，登录限流与 SSO 限流共用同一套判断）。
 *
 * <p>应用跑在 nginx 后面（`docker-compose.yml` 里的 nginx 会写 `X-Forwarded-For` / `X-Real-IP`），
 * 直接用 {@code getRemoteAddr()} 拿到的是 nginx 容器的地址——那样"按 IP 限流"会变成"全局限流"。
 * 所以优先取 `X-Forwarded-For` 的第一段，取不到再回落。
 */
public final class ClientIps {

    private ClientIps() {
    }

    public static String of(jakarta.servlet.http.HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
