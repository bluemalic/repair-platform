package com.bluemalic.repair.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis 固定窗口计数器 —— 限流的底层原语。两个调用方：
 *
 * <ol>
 *   <li>{@code RateLimitInterceptor}：按登录用户计数的接口（报修码查询、扫码到场、文件上传）</li>
 *   <li>{@code AuthServiceImpl.login}：按<b>账号</b>计数的登录接口</li>
 * </ol>
 *
 * <p><b>为什么登录那处不在拦截器里</b>：计数维度是"账号 + 租户"，而账号在请求体里。
 * 拦截器读一次 {@code getInputStream()}，后面的 Controller 就读不到 body 了（要绕开就得加一个
 * 缓存整个请求体的 Filter，而上传接口的 body 有 6MB，代价不对等）。所以它只能在业务层计。
 * 既然分了两个调用方，Redis 逻辑就不能各写一份——"INCR 与 EXPIRE 必须在一个脚本里"这条教训
 * 维护两份迟早会漂。
 *
 * <p><b>降级策略（与 docs/01 §5 一致）</b>：Redis 不可用时返回 {@code null}，调用方一律放行。
 * 限流防的是滥用，不是业务正确性；为了它把登录或扫码整条链路拦住，代价远大于收益。
 * 降级提示只打一次 WARN，之后转 DEBUG——Redis 挂掉时每个请求都 WARN 会把日志刷爆，真正的问题反而看不见。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    /** 计数键前缀。测试用它清理计数（Redis 里的数据不随测试事务回滚）。 */
    public static final String KEY_PREFIX = "rate:";

    private static final RedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redis;

    private final AtomicBoolean degradedWarned = new AtomicBoolean();

    /**
     * 把 {@code key} 的计数加一，返回加完之后的值（首次调用会同时设上窗口 TTL）。
     *
     * @return 计数；Redis 不可用或拿不到返回值时为 {@code null}，调用方应放行
     */
    public Long increment(String key, int windowSeconds) {
        try {
            Long count = redis.execute(INCR_WITH_TTL, List.of(key), String.valueOf(windowSeconds));
            degradedWarned.set(false);
            return count;
        } catch (Exception e) {
            if (degradedWarned.compareAndSet(false, true)) {
                log.warn("限流计数失败，本次起放行后续请求（Redis 恢复后自动重新计数）: {}", e.getMessage());
            } else {
                log.debug("限流计数失败，放行", e);
            }
            return null;
        }
    }
}
