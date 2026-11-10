package com.bluemalic.repair.sso.mock;

import com.bluemalic.repair.sso.SsoProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **演示用的 Mock 身份提供方**（阶段 7 · ADR-022）。
 *
 * <p>它的存在是为了让"统一认证"这条链路**在本机能跑通、演示站能点通**，而不需要先有一所学校的
 * 真实 IdP。它同时实现了两种协议（OAuth2 授权码 + PKCE、CAS ticket 校验），
 * 于是 {@code SsoProvider} 的两个实现都有一条端到端的验证路径。
 *
 * <p><b>默认关闭</b>（{@code repair.sso.mock-idp-enabled=false}）：它**不做任何口令校验**，
 * 点一下就以某个身份"登录"——在生产环境开着它等于给所有人发任意账号的登录入口。
 * 关闭时所有端点返回 404。
 *
 * <p>页面上的身份是演示账号（`20260001` 学生 / `worker01` 维修工 / `admin` 后勤），
 * 外加一个"平台里没有的学号"用来演示"认证通过但没有账号"的提示。
 */
@RestController
@RequestMapping("/api/mock-idp")
@RequiredArgsConstructor
public class MockIdpController {

    private record MockUser(String username, String label) {
    }

    /** 演示身份：前三个在演示租户里真实存在，最后一个专门用来演示"认证通过但没有账号"。 */
    private static final List<MockUser> USERS = List.of(
            new MockUser("20260001", "学生 · 学号 20260001"),
            new MockUser("worker01", "维修工 · 工号 worker01"),
            new MockUser("admin", "后勤管理 · admin"),
            new MockUser("20269999", "平台里没有这个学号（看看失败提示）"));

    private final MockIdpStore store;
    private final SsoProperties properties;

    // ==================== OAuth2 ====================

    @GetMapping("/oauth2/authorize")
    public ResponseEntity<String> authorize(
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "code_challenge", required = false) String codeChallenge) {
        if (!properties.isMockIdpEnabled()) {
            return ResponseEntity.notFound().build();
        }
        String form = page("Mock 统一身份认证（OAuth2）", "/api/mock-idp/oauth2/approve",
                hidden("redirect_uri", redirectUri) + hidden("state", state)
                        + hidden("code_challenge", codeChallenge) + hidden("client_id", clientId));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8").body(form);
    }

    @PostMapping("/oauth2/approve")
    public ResponseEntity<Void> approve(@RequestParam String subject,
                                        @RequestParam("redirect_uri") String redirectUri,
                                        @RequestParam(required = false) String state,
                                        @RequestParam(value = "code_challenge", required = false) String codeChallenge) {
        if (!properties.isMockIdpEnabled()) {
            return ResponseEntity.notFound().build();
        }
        String code = store.issueOauthCode(subject, redirectUri, codeChallenge);
        return redirect(back(redirectUri, "code", code, "state", state));
    }

    @PostMapping("/oauth2/token")
    public ResponseEntity<Map<String, Object>> token(@RequestParam Map<String, String> form) {
        if (!properties.isMockIdpEnabled()) {
            return ResponseEntity.notFound().build();
        }
        String subject = store.consumeOauthCode(form.get("code"), form.get("redirect_uri"),
                form.get("code_verifier"));
        if (subject == null) {
            // 与 RFC 6749 的错误格式一致：invalid_grant
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_grant"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", store.issueAccessToken(subject));
        body.put("token_type", "Bearer");
        body.put("expires_in", 600);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/oauth2/userinfo")
    public ResponseEntity<Map<String, Object>> userinfo(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!properties.isMockIdpEnabled()) {
            return ResponseEntity.notFound().build();
        }
        String subject = authorization != null && authorization.startsWith("Bearer ")
                ? store.subjectOf(authorization.substring("Bearer ".length()).trim()) : null;
        if (subject == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_token"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub", subject);
        // 与 `tenant_sso_config.username_attribute` 的默认值一致：登录名这个字段
        body.put("username", subject);
        body.put("name", displayNameOf(subject));
        return ResponseEntity.ok(body);
    }

    // ==================== CAS ====================

    @GetMapping("/cas/login")
    public ResponseEntity<String> casLogin(@RequestParam("service") String service) {
        if (!properties.isMockIdpEnabled()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8")
                .body(page("Mock 统一身份认证（CAS）", "/api/mock-idp/cas/approve", hidden("service", service)));
    }

    @PostMapping("/cas/approve")
    public ResponseEntity<Void> casApprove(@RequestParam String subject, @RequestParam("service") String service) {
        if (!properties.isMockIdpEnabled()) {
            return ResponseEntity.notFound().build();
        }
        String ticket = store.issueCasTicket(subject, service);
        return redirect(back(service, "ticket", ticket));
    }

    @GetMapping("/cas/validate")
    public ResponseEntity<Map<String, Object>> casValidate(@RequestParam String ticket,
                                                           @RequestParam(required = false) String service) {
        if (!properties.isMockIdpEnabled()) {
            return ResponseEntity.notFound().build();
        }
        MockIdpStore.CasValidation validation = store.consumeCasTicket(ticket, service);
        if (validation.subject() == null) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("code", "INVALID_TICKET");
            // Mock 专有：把原因与两侧的 service 都回出来，联调时一眼看出差在哪
            failure.put("reason", validation.reason());
            failure.put("receivedService", validation.receivedService());
            failure.put("expectedService", validation.expectedService());
            return ResponseEntity.ok(Map.of("serviceResponse", Map.of("authenticationFailure", failure)));
        }
        return ResponseEntity.ok(Map.of("serviceResponse", Map.of("authenticationSuccess",
                Map.of("user", validation.subject(), "attributes", Map.of()))));
    }

    // ==================== 页面 ====================

    private String page(String title, String action, String hiddenFields) {
        StringBuilder buttons = new StringBuilder();
        for (MockUser user : USERS) {
            buttons.append("<button type=\"submit\" name=\"subject\" value=\"")
                    .append(HtmlUtils.htmlEscape(user.username())).append("\">")
                    .append(HtmlUtils.htmlEscape(user.label())).append("</button>\n");
        }
        return """
                <!doctype html>
                <html lang="zh-CN"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s</title>
                <style>
                  body{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;background:#f5f6f8;
                       display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0}
                  .card{background:#fff;border-radius:12px;padding:28px 32px;max-width:420px;width:92%%;
                        box-shadow:0 6px 24px rgba(0,0,0,.08)}
                  h1{font-size:18px;margin:0 0 8px}
                  p{color:#666;font-size:13px;line-height:1.6;margin:0 0 18px}
                  button{display:block;width:100%%;margin:8px 0;padding:11px 14px;border:1px solid #d9dce1;
                         border-radius:8px;background:#fff;font-size:14px;cursor:pointer;text-align:left}
                  button:hover{border-color:#409eff;color:#409eff}
                  .mock{background:#fff7e6;color:#8a5a00;border:1px solid #ffe1a8;border-radius:8px;
                        padding:8px 10px;font-size:12px;margin-bottom:16px}
                </style></head><body>
                <div class="card">
                  <h1>%s</h1>
                  <div class="mock">演示用 Mock IdP：<b>点击即登录</b>，不做口令校验。真实部署时这里是学校的统一认证页面。</div>
                  <p>选择一个身份继续：</p>
                  <form method="post" action="%s">
                  %s
                  %s
                  </form>
                </div></body></html>
                """.formatted(HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(title),
                HtmlUtils.htmlEscape(action), hiddenFields, buttons);
    }

    private String hidden(String name, String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return "<input type=\"hidden\" name=\"" + HtmlUtils.htmlEscape(name)
                + "\" value=\"" + HtmlUtils.htmlEscape(value) + "\">\n";
    }

    private String displayNameOf(String subject) {
        return USERS.stream().filter(user -> user.username().equals(subject))
                .map(MockUser::label).findFirst().orElse(subject);
    }

    /** 在回跳地址后追加查询参数（保留原有参数）。 */
    private String back(String base, String... pairs) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (StringUtils.hasText(pairs[i + 1])) {
                builder.queryParam(pairs[i], pairs[i + 1]);
            }
        }
        return builder.build().encode().toUriString();
    }

    private ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }
}
