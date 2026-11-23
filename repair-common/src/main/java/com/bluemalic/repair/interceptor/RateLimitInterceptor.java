package com.bluemalic.repair.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.session.SaSession;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.RateLimiter;
import com.bluemalic.repair.config.RateLimitRule;
import com.bluemalic.repair.service.CurrentTenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * 基于 Redis 固定窗口的接口限流（配合 {@link RateLimit} 注解使用）。
 *
 * <p>目前用于"吃报修码"、图片上传与付费 AI 接口：报修码是 6 位数字、租户内唯一（ADR-004），
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
 *   <li>Redis 不可用时，普通接口保持历史放行策略；AI 是付费能力，计数不可用时 fail-closed。</li>
 * </ol>
 *
 * <p>顺序由 {@code RateLimitConfig} 显式排在 Sa-Token 之后：计数以登录用户为维度，
 * 得先有登录态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String KEY_UPLOAD = "upload";

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
            // 普通接口保持历史的 fail-open；AI 与上传会产生外部费用，计数不可用时必须 fail-closed。
            if (isCostSensitive(annotated.key())) {
                throw rateLimitUnavailable(annotated.key());
            }
            return true;
        }
        if (count > maxRequests) {
            // 只记键与计数：键里有用户 ID 便于排查，但绝不能记 requestURI——它带着用户这次尝试的报修码
            log.warn("触发限流 key={} 档位={} 第 {} 次请求，阈值 {}/{}s",
                    key, annotated.key(), count, maxRequests, rule.getWindowSeconds());
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
        }

        if (isCostSensitive(annotated.key())) {
            // AI 用部署级总窗口；上传按租户分桶，避免一个学校耗尽其他租户的配额。
            Long globalCount = rateLimiter.increment(globalKey(annotated.key()),
                    rule.getWindowSeconds());
            if (globalCount == null) {
                throw rateLimitUnavailable(annotated.key());
            }
            int globalMaxRequests = rule.globalMaxRequestsFor(annotated.key());
            if (globalCount > globalMaxRequests) {
                log.warn("触发全局限流 tier={} count={} threshold={}/{}s",
                        annotated.key(), globalCount, globalMaxRequests, rule.getWindowSeconds());
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
            }

            if (RateLimitRule.KEY_AI.equals(annotated.key())) {
                String dailyKey = aiDailyKey();
                Long dailyCount = rateLimiter.increment(dailyKey, secondsUntilUtcDayEnds());
                if (dailyCount == null) {
                    throw rateLimitUnavailable(annotated.key());
                }
                if (dailyCount > rule.getAiDailyMaxRequests()) {
                    log.warn("触发 AI 日配额 count={} threshold={} key={}",
                            dailyCount, rule.getAiDailyMaxRequests(), dailyKey);
                    throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                            "AI 今日（UTC）请求额度已用尽，请联系管理员");
                }
            }
        }
        return true;
    }

    private String aiDailyKey() {
        return RateLimiter.KEY_PREFIX + "global:tier:ai:day:"
                + Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
    }

    private int secondsUntilUtcDayEnds() {
        ZonedDateTime now = Instant.now().atZone(ZoneOffset.UTC);
        ZonedDateTime tomorrow = now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE,
                Duration.between(now, tomorrow).getSeconds()));
    }

    private boolean isCostSensitive(String key) {
        return RateLimitRule.KEY_AI.equals(key) || KEY_UPLOAD.equals(key);
    }

    private String globalKey(String key) {
        if (RateLimitRule.KEY_AI.equals(key)) {
            return RateLimiter.KEY_PREFIX + "global:tier:ai";
        }
        SaSession session = StpUtil.getSession(false);
        Object tenantId = session == null ? null : session.get(CurrentTenantService.SESSION_TENANT_KEY);
        if (tenantId == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "无法确定租户，上传限流暂不可用");
        }
        return RateLimiter.KEY_PREFIX + "global:tenant:" + tenantId + ":tier:upload";
    }

    private BizException rateLimitUnavailable(String key) {
        if (RateLimitRule.KEY_AI.equals(key)) {
            return new BizException(ErrorCode.AI_MODEL_UNAVAILABLE, "AI 限流服务暂不可用");
        }
        return new BizException(ErrorCode.SYSTEM_ERROR, "上传限流服务暂不可用，请稍后重试");
    }
}
