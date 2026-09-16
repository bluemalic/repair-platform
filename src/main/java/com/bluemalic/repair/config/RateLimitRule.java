package com.bluemalic.repair.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 接口限流的阈值配置（与 {@code TimeoutRule} 同一套路：集中在配置类里，环境变量可覆盖）。
 *
 * <ul>
 *   <li>{@code REPAIR_RATE_LIMIT_MAX_REQUESTS}：同一用户同一接口在窗口内的最大请求数（默认 60）</li>
 *   <li>{@code REPAIR_RATE_LIMIT_WINDOW_SECONDS}：窗口长度，秒（默认 60）</li>
 * </ul>
 *
 * <p>默认值按"真实用户碰不到、枚举者撞得到"来定：扫一次码查一次位置，正常使用一分钟内不会有
 * 第二次；而枚举 6 位数字码需要百万次量级，60 次/分钟意味着一个账号即使一刻不停地试，
 * 一晚也只能覆盖几万个组合。
 */
@Getter
@Component
public class RateLimitRule {

    private final int maxRequests;

    private final int windowSeconds;

    public RateLimitRule(@Value("${repair.rate-limit.max-requests:60}") int maxRequests,
                         @Value("${repair.rate-limit.window-seconds:60}") int windowSeconds) {
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }
}
