package com.bluemalic.repair.sso;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 统一认证的运行参数（阶段 7 · ADR-022）。
 *
 * <p>SSO 的**学校侧配置**在数据库里（`tenant_sso_config`，平台运营维护）；这里的是**实例级**开关与时长，
 * 走环境变量（`REPAIR_SSO_*`）。
 */
@Getter
@Component
public class SsoProperties {

    /**
     * 是否装配演示用的 Mock IdP（`/mock-idp/**`）。**默认关闭**：它不做任何口令校验，
     * 只是"点一下就用某个身份登录"的演示工具；开着它等于给所有人发任意账号的登录入口。
     * 只应该在演示站/本地打开（打开方法见 docs/05）。
     */
    private final boolean mockIdpEnabled;

    /** 授权跳转的 state 有效期（一次性）：够用户在学校 IdP 页面登录即可，不宜过长 */
    private final int stateTtlSeconds;

    /** 回调成功后发给前端的一次性登录码有效期（前端拿到后立刻换 token） */
    private final int loginCodeTtlSeconds;

    /** 每个来源 IP 每分钟允许发起的授权/回调次数（防刷 state 与刷 IdP 校验） */
    private final int maxAttemptsPerMinute;

    /** 调 IdP（换 token / 取用户信息 / 校验 ticket）的超时：外部系统不能把登录拖死 */
    private final int httpTimeoutSeconds;

    /**
     * 站点对外的基地址（如 `https://repair.example.edu.cn`）。**回调地址由它拼**：
     * `{publicBaseUrl}/api/auth/sso/callback`。
     *
     * <p>为什么不能只靠请求推导：应用在 nginx 后面，nginx 的 `Host` 不带端口
     * （`$host` 只保留主机名）→ 站点跑在非 80/443 端口时，推导出来的回调地址会丢掉端口
     * （演示站 :8080 上是实测踩到的）。这个值留空时退回"按当前请求推导"，
     * 适合直连或标准端口的部署；反代在非标准端口时必须显式配。
     */
    private final String publicBaseUrl;

    public SsoProperties(@Value("${repair.sso.mock-idp-enabled:false}") boolean mockIdpEnabled,
                         @Value("${repair.sso.state-ttl-seconds:300}") int stateTtlSeconds,
                         @Value("${repair.sso.login-code-ttl-seconds:120}") int loginCodeTtlSeconds,
                         @Value("${repair.sso.max-attempts-per-minute:30}") int maxAttemptsPerMinute,
                         @Value("${repair.sso.http-timeout-seconds:5}") int httpTimeoutSeconds,
                         @Value("${repair.sso.public-base-url:}") String publicBaseUrl) {
        if (stateTtlSeconds < 30) {
            throw new IllegalStateException("统一认证 state 有效期不能少于 30 秒，请检查 repair.sso.state-ttl-seconds");
        }
        if (loginCodeTtlSeconds < 10) {
            throw new IllegalStateException("登录码有效期不能少于 10 秒，请检查 repair.sso.login-code-ttl-seconds");
        }
        if (maxAttemptsPerMinute < 1) {
            throw new IllegalStateException("统一认证的每分钟上限必须大于 0，请检查 repair.sso.max-attempts-per-minute");
        }
        if (httpTimeoutSeconds < 1) {
            throw new IllegalStateException("调 IdP 的超时必须大于 0，请检查 repair.sso.http-timeout-seconds");
        }
        this.mockIdpEnabled = mockIdpEnabled;
        this.stateTtlSeconds = stateTtlSeconds;
        this.loginCodeTtlSeconds = loginCodeTtlSeconds;
        this.maxAttemptsPerMinute = maxAttemptsPerMinute;
        this.httpTimeoutSeconds = httpTimeoutSeconds;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
    }
}
