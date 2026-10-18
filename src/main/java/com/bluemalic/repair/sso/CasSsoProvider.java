package com.bluemalic.repair.sso;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.entity.TenantSsoConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAS 2.0/3.0（阶段 7 · ADR-022）。
 *
 * <p>比 OAuth2 少两步：浏览器带 {@code ticket} 回来，服务端拿 {@code ticket + service} 调
 * {@code serviceValidate} **直接换用户名**（CAS 没有 token，也没有 userinfo）。
 *
 * <p><b>校验地址要带 {@code format=JSON}</b>：Apereo CAS 支持 JSON 输出；万一对面只回 XML，
 * 这里退回用正则取 {@code <cas:user>}（不为了一个字段引 XML 解析器）。
 *
 * <p>ticket 是**一次性**的，被 CAS 服务端消费；重复校验会返回 {@code authenticationFailure}，
 * 这里把它当成"认证失败"（正常现象：用户刷新了回调页）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CasSsoProvider implements SsoProvider {

    /** XML 兜底：`<cas:user>20260001</cas:user>`（命名空间前缀可能是 cas: 也可能没有） */
    private static final Pattern XML_USER = Pattern.compile("<(?:[A-Za-z0-9]+:)?user>([^<]+)</(?:[A-Za-z0-9]+:)?user>");

    private final SsoHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public SsoProtocol protocol() {
        return SsoProtocol.CAS;
    }

    @Override
    public String authorizationUrl(TenantSsoConfig config, String state, String redirectUri, String codeChallenge) {
        if (!StringUtils.hasText(config.getCasLoginUrl())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "这所学校的统一认证还没配好：CAS 登录地址");
        }
        // CAS 只回 ticket，不会把 state 带回来——所以把 state 嵌进 service 的查询串，
        // 回调时从 URL 里取（CAS 会在 service 后面追加 &ticket=...）。
        // 注意：这要求学校的 CAS 对 service 的登记允许带查询串（多数按前缀/正则匹配，允许）
        String service = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("state", state)
                .build().encode().toUriString();
        return UriComponentsBuilder.fromUriString(config.getCasLoginUrl())
                .queryParam("service", service)
                .build().encode().toUriString();
    }

    @Override
    public SsoIdentity authenticate(TenantSsoConfig config, String ticket, String redirectUri, String codeVerifier) {
        if (!StringUtils.hasText(ticket)) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "统一认证失败：没有收到 ticket");
        }
        if (!StringUtils.hasText(config.getCasValidateUrl())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "这所学校的统一认证还没配好：CAS 校验地址");
        }
        String url = UriComponentsBuilder.fromUriString(config.getCasValidateUrl())
                .queryParam("service", redirectUri)
                .queryParam("ticket", ticket)
                .queryParam("format", "JSON")
                .build().encode().toUriString();
        String body = httpClient.get(url, null);

        String username = parseUsername(body, config.getTenantId());
        return new SsoIdentity(username, username.trim(), null);
    }

    /** 先按 JSON 取 {@code serviceResponse.authenticationSuccess.user}，取不到再按 XML 兜底。 */
    private String parseUsername(String body, Long tenantId) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode success = root.path("serviceResponse").path("authenticationSuccess");
            if (!success.isMissingNode()) {
                String user = success.path("user").asText(null);
                if (StringUtils.hasText(user)) {
                    return user;
                }
            }
        } catch (Exception ignored) {
            // 不是 JSON：继续走 XML 兜底
        }
        Matcher matcher = XML_USER.matcher(body == null ? "" : body);
        if (matcher.find()) {
            return matcher.group(1);
        }
        log.warn("统一认证：CAS 校验没有返回用户 tenantId={} body={}", tenantId,
                body == null ? "" : body.substring(0, Math.min(200, body.length())));
        throw new BizException(ErrorCode.LOGIN_FAILED, "统一认证失败：学校身份认证服务没有返回有效身份（ticket 可能已过期）");
    }
}
