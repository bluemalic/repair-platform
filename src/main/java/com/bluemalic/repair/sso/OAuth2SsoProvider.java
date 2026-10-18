package com.bluemalic.repair.sso;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.entity.TenantSsoConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OAuth2 授权码模式 + PKCE（阶段 7 · ADR-022）。
 *
 * <p>三步：浏览器带 {@code code} 回来 → 服务端用 {@code code + code_verifier} 换 access token →
 * 用 token 查 userinfo 取登录名。
 *
 * <p><b>为什么带 PKCE</b>：这是公共客户端（H5）也能安全使用授权码模式的标准做法，
 * 而且**不增加部署成本**（不依赖 IdP 支持 confidential client）。challenge 用 S256。
 *
 * <p><b>登录名取哪个字段</b>由配置的 {@code username_attribute} 决定（默认 `username`）：
 * 不同学校的 IdP 返回的字段名不一样（`uid` / `account` / `studentNo`…），写死一个就等于只支持一所学校。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SsoProvider implements SsoProvider {

    private static final String DEFAULT_SCOPE = "openid profile";

    private final SsoHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public SsoProtocol protocol() {
        return SsoProtocol.OAUTH2;
    }

    @Override
    public String authorizationUrl(TenantSsoConfig config, String state, String redirectUri, String codeChallenge) {
        requireConfigured(config.getAuthorizeUrl(), "OAuth2 授权地址");
        requireConfigured(config.getClientId(), "OAuth2 客户端 ID");
        return UriComponentsBuilder.fromUriString(config.getAuthorizeUrl())
                .queryParam("response_type", "code")
                .queryParam("client_id", config.getClientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", state)
                .queryParam("scope", StringUtils.hasText(config.getScope()) ? config.getScope() : DEFAULT_SCOPE)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build().encode().toUriString();
    }

    @Override
    public SsoIdentity authenticate(TenantSsoConfig config, String code, String redirectUri, String codeVerifier) {
        if (!StringUtils.hasText(code)) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "统一认证失败：没有收到授权码");
        }
        requireConfigured(config.getTokenUrl(), "OAuth2 换 token 地址");
        requireConfigured(config.getUserinfoUrl(), "OAuth2 用户信息地址");

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", config.getClientId());
        if (StringUtils.hasText(config.getClientSecret())) {
            form.add("client_secret", config.getClientSecret());
        }
        if (StringUtils.hasText(codeVerifier)) {
            // PKCE：IdP 会用 challenge 校验它，防止授权码被别的客户端截走
            form.add("code_verifier", codeVerifier);
        }

        JsonNode tokenResponse = readJson(httpClient.postForm(config.getTokenUrl(), form), "换取 token");
        String accessToken = tokenResponse.path("access_token").asText(null);
        if (!StringUtils.hasText(accessToken)) {
            log.warn("统一认证：IdP 没有返回 access_token tenantId={}", config.getTenantId());
            throw new BizException(ErrorCode.LOGIN_FAILED, "统一认证失败：身份认证服务没有返回 token");
        }

        JsonNode userinfo = readJson(httpClient.get(config.getUserinfoUrl(), accessToken), "读取用户信息");
        String attribute = StringUtils.hasText(config.getUsernameAttribute())
                ? config.getUsernameAttribute() : "username";
        String username = userinfo.path(attribute).asText(null);
        if (!StringUtils.hasText(username)) {
            log.warn("统一认证：用户信息里没有配置的登录名属性 tenantId={} attribute={} keys={}",
                    config.getTenantId(), attribute, fieldNames(userinfo));
            throw new BizException(ErrorCode.LOGIN_FAILED,
                    "统一认证失败：身份认证服务没有返回登录名（字段 " + attribute + "）");
        }
        String subject = userinfo.path("sub").asText(username);
        String realName = userinfo.path("name").asText(null);
        return new SsoIdentity(subject, username.trim(), realName);
    }

    private JsonNode readJson(String body, String what) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("统一认证：{} 的响应不是合法 JSON（{}）", what, e.getMessage());
            throw new BizException(ErrorCode.LOGIN_FAILED, "统一认证失败：身份认证服务的响应格式不对");
        }
    }

    private String fieldNames(JsonNode node) {
        var names = new java.util.ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names.toString();
    }

    private void requireConfigured(String value, String what) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "这所学校的统一认证还没配好：" + what);
        }
    }
}
