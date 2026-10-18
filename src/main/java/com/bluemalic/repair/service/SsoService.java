package com.bluemalic.repair.service;

import com.bluemalic.repair.vo.LoginVO;
import com.bluemalic.repair.vo.SsoAvailableVO;

/**
 * 学校统一身份认证（SSO）的登录入口（阶段 7 · ADR-022）。
 *
 * <p>和账号密码登录是**同一个机制的第二个入口**：
 * <ol>
 *   <li>{@link #authorize}：把浏览器送去学校的 IdP（带上一次性 state 与 PKCE）；</li>
 *   <li>{@link #callback}：IdP 回来时用 code/ticket 换身份，在**这所学校自己的库**里找到账号，
 *       然后只发一个**一次性登录码**（token 不进 URL）；</li>
 *   <li>{@link #exchange}：前端用登录码换 token——这一步才真正"完成登录"：
 *       发 token、绑租户、写会话标记、写登录审计，全部复用账号密码登录那一套
 *       （{@code AuthService#completeLogin}）。</li>
 * </ol>
 *
 * <p><b>平台不自动建号</b>：SSO 只证明"这个人是这所学校的 20260001"，账号本身必须由学校在后台
 * 建好（配额、角色、数据权限都不该被外部认证系统绕过）。没有账号时给的是"联系后勤"的明确提示。
 */
public interface SsoService {

    /** 这所学校有没有开通统一认证（登录页决定是否显示入口）。 */
    SsoAvailableVO available(String tenantCode);

    /** 发起授权：返回要重定向去的 IdP 授权地址。{@code target} = ADMIN / H5。 */
    String authorize(String tenantCode, String target, String clientIp);

    /**
     * 处理 IdP 回调：返回要重定向回前端的地址（带 {@code ssoCode}，或 {@code ssoError/ssoMessage}）。
     *
     * @param code   OAuth2 的授权码
     * @param ticket CAS 的 ticket（CAS 会把它追加在 service 后面）
     */
    String callback(String code, String ticket, String state, String error, String clientIp);

    /** 用一次性登录码换 token。 */
    LoginVO exchange(String loginCode, String clientIp);
}
