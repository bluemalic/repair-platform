package com.bluemalic.repair.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.config.RateLimitRule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Redis 固定窗口的接口限流（配合 {@link RateLimit} 注解使用）。
 *
 * <p>目前只挂在"吃报修码"的两个接口上（{@code GET /api/tickets/by-code/{code}} 与
 * {@code POST /api/worker/tickets/{id}/arrive}）：报修码是 6 位数字、租户内唯一（ADR-004），
 * 是全项目唯一一处"猜一个短字符串就能拿到数据"的入口，不设限流等于把"扫完整个码空间"
 * 变成一条脚本就能完成的事（docs/01 §5「报修码随机生成、防枚举」）。
 *
 * <p>三个实现上的关键选择，改代码前先看这里：
 * <ol>
 *   <li><b>计数键取「登录用户 + 接口方法」，不取请求路径。</b> 路径里带着用户这次尝试的报修码，
 *       用它做键会让每次猜码各自独立计数、等于没有限流——这是本类最容易写错的一处。</li>
 *   <li><b>INCR 与 EXPIRE 放在一段 Lua 里。</b> 分两次调用时，那个把计数从 0 变成 1 的请求
 *       如果在两步之间失败（或进程退出），键就永久没有 TTL，这个用户会被自己的计数一直挡在
 *       门外，直到有人手工清键。脚本保证"要么都做、要么都不做"。</li>
 *   <li><b>Redis 不可用时放行而不是拦死。</b> 见 docs/01 §5 降级策略：限流防的是滥用，
 *       不是业务正确性，为了它把扫码报修整条链路拦住，代价远大于收益。</li>
 * </ol>
 *
 * <p>顺序由 {@code RateLimitConfig} 显式排在 Sa-Token 之后：计数以登录用户为维度，
 * 得先有登录态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

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
    private final RateLimitRule rule;

    /** 降级提示只打一次：Redis 挂掉时每个请求都 WARN 会把日志刷爆，真正的问题反而看不见。 */
    private final AtomicBoolean degradedWarned = new AtomicBoolean();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)
                || handlerMethod.getMethodAnnotation(RateLimit.class) == null) {
            return true;
        }
        Object loginId;
        try {
            loginId = StpUtil.getLoginIdDefaultNull();
        } catch (Exception e) {
            // 无 Sa-Token 上下文（例如非 Web 线程直接调用）→ 不计数，交由 SaInterceptor 判登录
            return true;
        }
        if (loginId == null) {
            // 未登录请求由 SaInterceptor 直接 401，这里不必也不该计数（没有用户维度可归属）
            return true;
        }

        String key = KEY_PREFIX + loginId + ":" + handlerMethod.getBeanType().getSimpleName()
                + "." + handlerMethod.getMethod().getName();
        Long count;
        try {
            count = redis.execute(INCR_WITH_TTL, List.of(key), String.valueOf(rule.getWindowSeconds()));
            degradedWarned.set(false);
        } catch (Exception e) {
            if (degradedWarned.compareAndSet(false, true)) {
                log.warn("限流计数失败，本次起放行后续请求（Redis 恢复后自动重新计数）: {}", e.getMessage());
            } else {
                log.debug("限流计数失败，放行", e);
            }
            return true;
        }
        if (count == null) {
            // 管道/事务模式下脚本返回值可能为空，同样按"拿不到计数就别拦人"处理
            return true;
        }
        if (count > rule.getMaxRequests()) {
            // 只记键与计数：键里有用户 ID 便于排查，但绝不能记 requestURI——它带着用户这次尝试的报修码
            log.warn("触发限流 key={} 第 {} 次请求，阈值 {}/{}s",
                    key, count, rule.getMaxRequests(), rule.getWindowSeconds());
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
        }
        return true;
    }
}
