package com.bluemalic.repair.sso;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 一次性**登录码**（阶段 7 · ADR-022）：回调与换取 token 之间的中转。
 *
 * <p>回调是浏览器的一跳，**不能把 token 放在跳转 URL 里**（会进浏览器历史、Referer、代理日志）。
 * 所以回调只发一个短命的随机码，前端拿着它立刻换 token——换取成功即删除（{@code GETDEL}），
 * 同一个码用第二次必然失败。
 *
 * <p>登录码里存的是 `tenantId|userId`：真正发 token 的动作发生在 exchange（那一步才写审计），
 * 所以"认证通过"与"完成登录"之间不会多出一条重复的登录审计。
 */
@Component
@RequiredArgsConstructor
public class SsoLoginCodeStore {

    private static final String PREFIX = "sso:login:";
    private static final Pattern SEPARATOR = Pattern.compile("\\|");

    private final StringRedisTemplate redis;
    private final SsoProperties properties;
    private final SsoStateStore stateStore;

    /** 发一个登录码（两次登录互不干扰：每次都是新的随机码）。 */
    public String issue(long tenantId, long userId) {
        String code = stateStore.randomToken();
        redis.opsForValue().set(PREFIX + code, tenantId + "|" + userId,
                Duration.ofSeconds(properties.getLoginCodeTtlSeconds()));
        return code;
    }

    /**
     * 兑换并删除。
     *
     * @return {@code [tenantId, userId]}；不存在（过期 / 伪造 / 已用过）返回 null
     */
    public long[] consume(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String value = redis.opsForValue().getAndDelete(PREFIX + code);
        if (value == null) {
            return null;
        }
        String[] parts = SEPARATOR.split(value);
        if (parts.length != 2) {
            return null;
        }
        try {
            return new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
