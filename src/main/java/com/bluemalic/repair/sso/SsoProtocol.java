package com.bluemalic.repair.sso;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;

/**
 * 支持的统一身份认证协议（阶段 7 · ADR-022）。
 *
 * <p>两种协议的区别只在"怎么问 IdP 要身份"：
 * <ul>
 *   <li>{@link #OAUTH2}：浏览器带 {@code code} 回来，服务端用 code + PKCE verifier 换 token，
 *       再拿 token 查用户信息；</li>
 *   <li>{@link #CAS}：浏览器带 {@code ticket} 回来，服务端拿 ticket 调 {@code serviceValidate}
 *       直接换用户名（CAS 没有 token / userinfo 这两步）。</li>
 * </ul>
 *
 * <p>适配器的边界就到这里：**协议之上的东西（账号映射、登录、审计、配额、数据权限）全部复用账号密码登录那一套**。
 */
public enum SsoProtocol {

    OAUTH2,
    CAS;

    /** 从配置的字符串解析；不认识的协议直接报错（宁可拒绝，也不要猜一个默认值）。 */
    public static SsoProtocol parse(String value) {
        if (value != null) {
            for (SsoProtocol protocol : values()) {
                if (protocol.name().equalsIgnoreCase(value.trim())) {
                    return protocol;
                }
            }
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "不支持的统一认证协议：" + value + "（只支持 OAUTH2 / CAS）");
    }
}
