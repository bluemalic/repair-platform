package com.bluemalic.repair.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 接口限流的阈值配置（与 {@code TimeoutRule} 同一套路：集中在配置类里，环境变量可覆盖）。
 *
 * <ul>
 *   <li>{@code REPAIR_RATE_LIMIT_MAX_REQUESTS}：按<b>登录用户</b>计数时，同一接口在窗口内的最大请求数（默认 60）</li>
 *   <li>{@code REPAIR_RATE_LIMIT_LOGIN_MAX_REQUESTS}：同一<b>账号</b>在窗口内最多发起几次登录（默认 10）</li>
 *   <li>{@code REPAIR_RATE_LIMIT_WINDOW_SECONDS}：窗口长度，秒（默认 60）</li>
 * </ul>
 *
 * <p>默认值按"真实用户碰不到、枚举者撞得到"来定：扫一次码查一次位置，正常使用一分钟内不会有
 * 第二次；而枚举 6 位数字码需要百万次量级，60 次/分钟意味着一个账号即使一刻不停地试，
 * 一晚也只能覆盖几万个组合。
 *
 * <p><b>登录档（10）为什么更严、而且按账号而不按 IP 计</b>：学号是公开信息（班级群里就有），
 * 最现实的威胁是"知道某个同学是谁，然后猜他的密码"——按账号计数正好精确打击它。
 * 而按 IP 计在校园网里会误伤：宿舍、机房的 NAT 出口后面成百上千人共享一个公网 IP，
 * 阈值按单个自然人的量级来定，早高峰的登录会被一起挡掉；放宽到不误伤，也就等于没有限流。
 *
 * <p>它<b>防不住</b>的是撞库与分布式爆破（每个账号只试一两次、或者换 IP），那要靠验证码或风控，
 * 不在本期范围内——把边界写在这里，免得看到"登录有限流"就以为登录安全了。
 */
@Getter
@Component
public class RateLimitRule {

    private final int maxRequests;

    private final int loginMaxRequests;

    private final int windowSeconds;

    public RateLimitRule(@Value("${repair.rate-limit.max-requests:60}") int maxRequests,
                         @Value("${repair.rate-limit.login-max-requests:10}") int loginMaxRequests,
                         @Value("${repair.rate-limit.window-seconds:60}") int windowSeconds) {
        this.maxRequests = maxRequests;
        this.loginMaxRequests = loginMaxRequests;
        this.windowSeconds = windowSeconds;
    }
}
