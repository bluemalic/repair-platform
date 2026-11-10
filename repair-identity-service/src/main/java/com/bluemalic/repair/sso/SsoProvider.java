package com.bluemalic.repair.sso;

import com.bluemalic.repair.entity.TenantSsoConfig;

/**
 * 统一身份认证协议适配器（阶段 7 · ADR-022）。
 *
 * <p>每个实现只做两件事：**给浏览器一个授权地址**、**用回调参数换出身份**。
 * 账号映射、登录发 token、审计、限流都不在这里——那些是登录机制（与账号密码登录共用）。
 *
 * <p><b>两个方法都可能抛 {@code BizException(LOGIN_FAILED)}</b>：IdP 连不上、返回格式不对、
 * token 换不出来都归到"统一认证失败"，文案给用户、细节进日志（不把 IdP 的原始响应回显出去，
 * 里面可能有凭据）。
 */
public interface SsoProvider {

    SsoProtocol protocol();

    /**
     * 浏览器要跳过去的授权地址。
     *
     * @param state         本次登录的状态（回调时校验，一次性）
     * @param redirectUri   回调地址（必须与在 IdP 那边登记的一致）
     * @param codeChallenge OAuth2 的 PKCE challenge（S256）；CAS 用不到
     */
    String authorizationUrl(TenantSsoConfig config, String state, String redirectUri, String codeChallenge);

    /**
     * 用回调参数换出身份。
     *
     * @param credential    OAuth2 的授权码；CAS 的 ticket（同一个参数位置）
     * @param redirectUri   不带 state 的回调地址（OAuth2 的 redirect_uri 必须与登记的一致）
     * @param state         本次登录的 state。**CAS 需要它**：ticket 绑定在 authorize 时的 service 上
     *                      （state 就嵌在 service 里），校验时必须用同一个字符串
     * @param codeVerifier  PKCE 的原文（OAuth2 用；CAS 传空）
     */
    SsoIdentity authenticate(TenantSsoConfig config, String credential, String redirectUri,
                             String state, String codeVerifier);
}
