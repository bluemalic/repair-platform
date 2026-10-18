package com.bluemalic.repair.sso;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 统一认证的**一次性 state**（阶段 7 · ADR-022）：防 CSRF，顺便把"这次登录属于哪个学校、
 * 回到哪个前端、PKCE 的 verifier"带到回调里。
 *
 * <p>为什么放 Redis 而不是 Session：发起授权时用户还没有登录态（这正是登录入口），
 * 没有 Session 可用；而 state 本来就该是"服务端签发的短命凭证"，Redis 的 TTL 正好表达它。
 *
 * <p>**只消费一次**（{@code GETDEL}）：重放同一个 state 会直接失败。这一点比"校验签名"更强——
 * 签名能防伪造，但防不住"把同一个回调链接再打开一次"。
 */
@Component
@RequiredArgsConstructor
public class SsoStateStore {

    private static final String PREFIX = "sso:state:";
    private static final String SEPARATOR = "|";
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile(Pattern.quote(SEPARATOR));

    private final StringRedisTemplate redis;
    private final SsoProperties properties;

    private final SecureRandom random = new SecureRandom();

    /** 发起授权时暂存的东西。{@code codeVerifier} 对 CAS 为空。 */
    public record PendingState(String tenantCode, String target, String codeVerifier) {
    }

    /** 生成并暂存一个 state，返回给浏览器（它会原样跟着 IdP 回来）。 */
    public String issue(String tenantCode, String target, String codeVerifier) {
        String state = randomToken();
        String value = tenantCode + SEPARATOR + target + SEPARATOR + (codeVerifier == null ? "" : codeVerifier);
        redis.opsForValue().set(PREFIX + state, value, Duration.ofSeconds(properties.getStateTtlSeconds()));
        return state;
    }

    /** 取出并删除；不存在（过期 / 伪造 / 已用过）返回 null。 */
    public PendingState consume(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String value = redis.opsForValue().getAndDelete(PREFIX + state);
        if (value == null) {
            return null;
        }
        String[] parts = SEPARATOR_PATTERN.split(value, 3);
        if (parts.length < 3) {
            return null;
        }
        return new PendingState(parts[0], parts[1], parts[2]);
    }

    /** 32 字节随机数的十六进制串（state / 登录码 / PKCE verifier 共用） */
    public String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** PKCE S256：BASE64URL(SHA256(verifier))，去掉末尾的 '='。 */
    public static String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("计算 PKCE challenge 失败", e);
        }
    }
}
