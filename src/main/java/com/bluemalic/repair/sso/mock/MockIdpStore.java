package com.bluemalic.repair.sso.mock;

import com.bluemalic.repair.sso.SsoStateStore;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 演示用 Mock IdP 的**临时凭据**（阶段 7 · ADR-022）。
 *
 * <p>只放内存：它服务的是"演示与本地联调"，进程重启后没换完的授权码失效是**可以接受**的
 * （真实 IdP 也不会让授权码活过重启）。放 Redis 反而让人误以为它是可靠存储。
 *
 * <p>三条与真实协议对齐的行为：
 * <ul>
 *   <li>授权码 / ticket **一次性**：换取即删除；</li>
 *   <li>都带过期时间；</li>
 *   <li>OAuth2 授权码绑定 PKCE challenge：换 token 时校验，防"授权码被别的客户端截走"。</li>
 * </ul>
 */
@Component
public class MockIdpStore {

    private static final Duration CODE_TTL = Duration.ofMinutes(2);
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);
    private static final int MAX_ENTRIES = 1000;

    private record Code(String subject, String target, String codeChallenge, Instant expiresAt) {
    }

    private record Token(String subject, Instant expiresAt) {
    }

    private final Map<String, Code> oauthCodes = new ConcurrentHashMap<>();
    private final Map<String, Token> accessTokens = new ConcurrentHashMap<>();
    private final Map<String, Code> casTickets = new ConcurrentHashMap<>();

    private final SsoStateStore random;

    public MockIdpStore(SsoStateStore random) {
        this.random = random;
    }

    /** 发授权码（{@code codeChallenge} 为空表示客户端没用 PKCE）。 */
    public String issueOauthCode(String subject, String redirectUri, String codeChallenge) {
        cleanup();
        String code = random.randomToken();
        oauthCodes.put(code, new Code(subject, redirectUri, codeChallenge, Instant.now().plus(CODE_TTL)));
        return code;
    }

    /**
     * 换 token 前校验授权码。
     *
     * @return 绑定过的 subject；码无效 / 过期 / redirect_uri 不一致 / PKCE 不匹配 → null
     */
    public String consumeOauthCode(String code, String redirectUri, String codeVerifier) {
        Code entry = code == null ? null : oauthCodes.remove(code);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            return null;
        }
        if (!entry.target().equals(redirectUri)) {
            return null;
        }
        if (entry.codeChallenge() != null && !entry.codeChallenge().isBlank()) {
            // 模拟真实 IdP 的 PKCE 校验：没给 verifier 或对不上都拒绝
            if (codeVerifier == null || !entry.codeChallenge().equals(SsoStateStore.codeChallenge(codeVerifier))) {
                return null;
            }
        }
        return entry.subject();
    }

    public String issueAccessToken(String subject) {
        cleanup();
        String token = random.randomToken();
        accessTokens.put(token, new Token(subject, Instant.now().plus(TOKEN_TTL)));
        return token;
    }

    public String subjectOf(String accessToken) {
        Token token = accessToken == null ? null : accessTokens.get(accessToken);
        if (token == null || token.expiresAt().isBefore(Instant.now())) {
            return null;
        }
        return token.subject();
    }

    public String issueCasTicket(String subject, String service) {
        cleanup();
        String ticket = "ST-" + random.randomToken();
        casTickets.put(ticket, new Code(subject, service, null, Instant.now().plus(CODE_TTL)));
        return ticket;
    }

    /** CAS ticket 一次性：校验即删除（与真实 CAS 一致，刷回调页会失败）。 */
    public String consumeCasTicket(String ticket, String service) {
        Code entry = ticket == null ? null : casTickets.remove(ticket);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            return null;
        }
        if (!entry.target().equals(service)) {
            return null;
        }
        return entry.subject();
    }

    /** 顺手清理过期项，并给意外增长设个上限（演示工具，不该成为内存泄漏点）。 */
    private void cleanup() {
        Instant now = Instant.now();
        oauthCodes.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        accessTokens.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        casTickets.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        if (oauthCodes.size() > MAX_ENTRIES) {
            oauthCodes.clear();
        }
        if (casTickets.size() > MAX_ENTRIES) {
            casTickets.clear();
        }
    }
}
