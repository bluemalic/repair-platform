package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.RateLimiter;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.entity.TenantSsoConfig;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.TenantMapper;
import com.bluemalic.repair.mapper.TenantSsoConfigMapper;
import com.bluemalic.repair.service.AuthService;
import com.bluemalic.repair.service.SsoService;
import com.bluemalic.repair.sso.SsoIdentity;
import com.bluemalic.repair.sso.SsoLoginCodeStore;
import com.bluemalic.repair.sso.SsoProperties;
import com.bluemalic.repair.sso.SsoProtocol;
import com.bluemalic.repair.sso.SsoProvider;
import com.bluemalic.repair.sso.SsoProviderFactory;
import com.bluemalic.repair.sso.SsoStateStore;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import com.bluemalic.repair.vo.LoginVO;
import com.bluemalic.repair.vo.SsoAvailableVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一身份认证的登录编排（阶段 7 · ADR-022）。
 *
 * <p>这个类只做**编排与安全边界**：状态校验、限流、账号查找、重定向地址（防开放重定向）、
 * 一次性登录码。协议细节在 {@code sso/} 下的两个适配器里；"真正登录"复用
 * {@link AuthService#completeLogin}——与账号密码登录是同一条路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SsoServiceImpl implements SsoService {

    private static final String TARGET_ADMIN = "ADMIN";
    private static final String TARGET_H5 = "H5";

    private final TenantMapper tenantMapper;
    private final TenantSsoConfigMapper ssoConfigMapper;
    private final SysUserMapper sysUserMapper;
    private final TenantSchemaCatalog schemaCatalog;
    private final AuthService authService;
    private final SsoProviderFactory providerFactory;
    private final SsoStateStore stateStore;
    private final SsoLoginCodeStore loginCodeStore;
    private final SsoProperties properties;
    private final RateLimiter rateLimiter;

    @Override
    public SsoAvailableVO available(String tenantCode) {
        Tenant tenant = requireTenant(tenantCode);
        TenantSsoConfig config = configOf(tenant.getId());
        SsoAvailableVO vo = new SsoAvailableVO();
        vo.setTenantName(tenant.getName());
        boolean enabled = config != null && config.enabled();
        vo.setEnabled(enabled);
        vo.setProtocol(enabled ? config.getProtocol() : null);
        return vo;
    }

    @Override
    public String authorize(String tenantCode, String target, String clientIp) {
        requireNotRateLimited("authorize", clientIp);
        Tenant tenant = requireTenant(tenantCode);
        TenantSsoConfig config = requireEnabledConfig(tenant.getId());
        String normalizedTarget = normalizeTarget(target);

        // PKCE：verifier 留在服务端（state 里），challenge 跟着浏览器去 IdP。
        // CAS 用不到它，但生成一份无害——两种协议共用同一段状态代码更不容易分叉
        String verifier = stateStore.randomToken();
        String state = stateStore.issue(tenant.getCode(), normalizedTarget, verifier);
        SsoProvider provider = providerFactory.of(SsoProtocol.parse(config.getProtocol()));
        String authorizationUrl = provider.authorizationUrl(config, state, callbackUrl(),
                SsoStateStore.codeChallenge(verifier));
        log.info("发起统一认证 tenantId={} protocol={} target={}", tenant.getId(), config.getProtocol(), normalizedTarget);
        return authorizationUrl;
    }

    @Override
    public String callback(String code, String ticket, String state, String error, String clientIp) {
        requireNotRateLimited("callback", clientIp);
        // state 一次性：重放同一个回调链接会直接失败（比"校验签名"更强，见 SsoStateStore）
        SsoStateStore.PendingState pending = stateStore.consume(state);
        if (pending == null) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "登录请求已失效或已经用过，请回到登录页重新发起统一认证");
        }
        Tenant tenant = requireTenant(pending.tenantCode());
        TenantSsoConfig config = requireEnabledConfig(tenant.getId());
        String target = pending.target();

        if (StringUtils.hasText(error)) {
            // 用户在 IdP 那边点了取消，或 IdP 自己报错：带回去给前端提示，不当成系统故障
            log.info("统一认证被取消或失败 tenantId={} error={}", tenant.getId(), error);
            return redirectWithError(config, target, "统一认证未完成（" + error + "）");
        }

        try {
            SsoProtocol protocol = SsoProtocol.parse(config.getProtocol());
            // 两种协议把凭据放在不同的参数里：OAuth2 = code，CAS = ticket（CAS 追加在 service 后）
            String credential = protocol == SsoProtocol.CAS ? ticket : code;
            SsoIdentity identity = providerFactory.of(protocol)
                    .authenticate(config, credential, callbackUrl(), state, pending.codeVerifier());
            SysUser user = findUser(tenant, identity.username());
            if (user == null) {
                log.info("统一认证通过但平台里没有对应账号 tenantId={} username={}",
                        tenant.getId(), identity.username());
                return redirectWithError(config, target,
                        "统一认证已通过，但平台里没有 " + identity.username() + " 对应的账号，请联系学校后勤");
            }
            if (!Integer.valueOf(1).equals(user.getStatus())) {
                return redirectWithError(config, target, "账号已被禁用，请联系学校后勤");
            }
            // 只发一次性登录码：token 绝不进 URL（浏览器历史 / Referer / 代理日志都会留痕）
            String loginCode = loginCodeStore.issue(tenant.getId(), user.getId());
            log.info("统一认证通过 tenantId={} userId={} subject={}",
                    tenant.getId(), user.getId(), identity.subject());
            Map<String, String> params = new LinkedHashMap<>();
            params.put("ssoCode", loginCode);
            return redirectTo(config, target, params);
        } catch (BizException e) {
            log.info("统一认证失败 tenantId={} code={} message={}",
                    tenant.getId(), e.getErrorCode().getCode(), e.getMessage());
            return redirectWithError(config, target, e.getMessage());
        } catch (Exception e) {
            log.error("统一认证回调未预期异常 tenantId={}", tenant.getId(), e);
            return redirectWithError(config, target, ErrorCode.SYSTEM_ERROR.getMessage());
        }
    }

    @Override
    public LoginVO exchange(String loginCode, String clientIp) {
        requireNotRateLimited("exchange", clientIp);
        long[] reference = loginCodeStore.consume(loginCode);
        if (reference == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "登录码已失效，请回到登录页重新发起统一认证");
        }
        Tenant tenant = requireTenantById(reference[0]);
        if (!Integer.valueOf(1).equals(tenant.getStatus())) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND);
        }
        // 账号在**这所学校自己的库**里（ADR-021：搬过家的学校数据在 repair_xxx）
        String routingKey = schemaCatalog.routingKeyFor(tenant.getId());
        SysUser user = TenantRoutingContext.call(routingKey, () -> sysUserMapper.selectById(reference[1]));
        if (user == null) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "账号不存在，请重新发起统一认证");
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        // 真正的"完成登录"发生在这里：发 token、绑租户、写会话标记与登录审计（与账号密码登录同一条路）。
        // 钉住租户库是必须的——登录审计要写进这所学校自己的库
        return TenantRoutingContext.call(routingKey, () -> authService.completeLogin(tenant, user));
    }

    // ==================== 内部 ====================

    private Tenant requireTenant(String tenantCode) {
        if (!StringUtils.hasText(tenantCode)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "学校编码不能为空");
        }
        Tenant tenant = TenantRoutingContext.callControl(() -> tenantMapper.selectOne(
                Wrappers.<Tenant>lambdaQuery().eq(Tenant::getCode, tenantCode.trim())));
        if (tenant == null || !Integer.valueOf(1).equals(tenant.getStatus())) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND);
        }
        return tenant;
    }

    private Tenant requireTenantById(long tenantId) {
        Tenant tenant = TenantRoutingContext.callControl(() -> tenantMapper.selectById(tenantId));
        if (tenant == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND);
        }
        return tenant;
    }

    private TenantSsoConfig configOf(long tenantId) {
        return TenantRoutingContext.callControl(() -> ssoConfigMapper.selectById(tenantId));
    }

    private TenantSsoConfig requireEnabledConfig(long tenantId) {
        TenantSsoConfig config = configOf(tenantId);
        if (config == null || !config.enabled()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "这所学校还没有开通统一身份认证登录");
        }
        return config;
    }

    private SysUser findUser(Tenant tenant, String username) {
        String routingKey = schemaCatalog.routingKeyFor(tenant.getId());
        return TenantRoutingContext.call(routingKey, () -> sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getTenantId, tenant.getId())
                        .eq(SysUser::getUsername, username)));
    }

    private String normalizeTarget(String target) {
        if (!StringUtils.hasText(target)) {
            return TARGET_ADMIN;
        }
        String normalized = target.trim().toUpperCase();
        if (!TARGET_ADMIN.equals(normalized) && !TARGET_H5.equals(normalized)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "登录目标只能是 ADMIN 或 H5");
        }
        return normalized;
    }

    /**
     * 回调地址。
     *
     * <p>优先用配置的 `repair.sso.public-base-url`：反代在非标准端口时，nginx 的 `Host` 不带端口，
     * 按请求推导会丢掉端口（演示站 :8080 实测）。没配时才退回当前请求（直连 / 标准端口够用）。
     */
    private String callbackUrl() {
        if (StringUtils.hasText(properties.getPublicBaseUrl())) {
            return properties.getPublicBaseUrl() + "/api/auth/sso/callback";
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/auth/sso/callback").build().toUriString();
    }

    private String redirectWithError(TenantSsoConfig config, String target, String message) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("ssoError", String.valueOf(ErrorCode.LOGIN_FAILED.getCode()));
        params.put("ssoMessage", message);
        return redirectTo(config, target, params);
    }

    /**
     * 回到前端。
     *
     * <p>**只允许用配置里的地址**：请求参数只能选 "ADMIN / H5"，不接受任意 URL——
     * 否则这个接口就是一个开放重定向（攻击者可以拿学校的域名把用户带去钓鱼页）。
     */
    private String redirectTo(TenantSsoConfig config, String target, Map<String, String> params) {
        String base = TARGET_H5.equals(target) ? config.getH5RedirectUrl() : config.getAdminRedirectUrl();
        if (!StringUtils.hasText(base)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "这所学校还没配置统一认证登录成功后的跳转地址（" + target + "）");
        }
        var builder = org.springframework.web.util.UriComponentsBuilder.fromUriString(base);
        params.forEach(builder::queryParam);
        return builder.build().encode().toUriString();
    }

    private void requireNotRateLimited(String scope, String clientIp) {
        String key = RateLimiter.KEY_PREFIX + "sso:" + scope + ":" + (StringUtils.hasText(clientIp) ? clientIp : "unknown");
        Long count = rateLimiter.increment(key, 60);
        if (count != null && count > properties.getMaxAttemptsPerMinute()) {
            log.warn("统一认证触发限流 scope={} ip={}", scope, clientIp);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }
}
