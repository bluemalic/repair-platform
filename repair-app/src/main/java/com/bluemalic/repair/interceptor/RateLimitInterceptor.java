package com.bluemalic.repair.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.RateLimiter;
import com.bluemalic.repair.config.RateLimitRule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 基于 Redis 固定窗口的接口限流（配合 {@link RateLimit} 注解使用）。
 *
 * <p>目前挂在"吃报修码"的两个接口上（{@code GET /api/tickets/by-code/{code}} 与
 * {@code POST /api/worker/tickets/{id}/arrive}）：报修码是 6 位数字、租户内唯一（ADR-004），
 * 是全项目最典型的"猜一个短字符串就能拿到数据"的入口，不设限流等于把"扫完整个码空间"
 * 变成一条脚本就能完成的事（docs/01 §5「报修码随机生成、防枚举」）。
 *
 * <p><b>登录接口的限流不在这里</b>：它的计数维度是"租户 + 账号"，而账号在请求体里，拦截器读不到
 * （读了 Controller 就读不到了）。那一处走 {@code AuthServiceImpl.login} + {@link RateLimiter}，
 * 阈值见 {@link RateLimitRule#getLoginMaxRequests()}。
 *
 * <p>三个实现上的关键选择，改代码前先看这里：
 * <ol>
 *   <li><b>计数键取「登录用户 + 接口方法」，不取请求路径。</b> 路径里带着用户这次尝试的报修码，
 *       用它做键会让每次猜码各自独立计数、等于没有限流——这是本类最容易写错的一处。</li>
 *   <li><b>INCR 与 EXPIRE 交给 {@link RateLimiter} 里的一段 Lua</b>，不在这里分两次调 Redis：
 *       那个把计数从 0 变成 1 的请求如果在两步之间失败，键就永久没有 TTL，这个用户会被自己的
 *       计数一直挡在门外，直到有人手工清键。</li>
 *   <li><b>Redis 不可用时放行而不是拦死</b>（即 {@code increment} 返回 null 时的处理）。</li>
 * </ol>
 *
 * <p>顺序由 {@code RateLimitConfig} 显式排在 Sa-Token 之后：计数以登录用户为维度，
 * 得先有登录态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;

    private final RateLimitRule rule;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimit annotated = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (annotated == null) {
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

        // 阈值按注解上的档位取（认不出的档位回落默认档）
        int maxRequests = rule.maxRequestsFor(annotated.key());
        // 计数键：有档位名的**按档位**计（AI 问数的 GET 流式与 POST 是同一笔开销，
        // 各自一份计数等于把成本上限翻倍）；没有档位名的按「用户 + 接口方法」——
        // 不取请求路径是类注释第 1 条讲的那个坑。
        String counted = annotated.key().isEmpty()
                ? handlerMethod.getBeanType().getSimpleName() + "." + handlerMethod.getMethod().getName()
                : "tier:" + annotated.key();
        String key = RateLimiter.KEY_PREFIX + loginId + ":" + counted;
        Long count = rateLimiter.increment(key, rule.getWindowSeconds());
        if (count == null) {
            // 拿不到计数就别拦人（Redis 挂了，或管道/事务模式下脚本返回空）
            return true;
        }
        if (count > maxRequests) {
            // 只记键与计数：键里有用户 ID 便于排查，但绝不能记 requestURI——它带着用户这次尝试的报修码
            log.warn("触发限流 key={} 档位={} 第 {} 次请求，阈值 {}/{}s",
                    key, annotated.key(), count, maxRequests, rule.getWindowSeconds());
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
        }
        return true;
    }
}
