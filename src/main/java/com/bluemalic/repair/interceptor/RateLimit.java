package com.bluemalic.repair.interceptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要限流的接口方法，由 {@link RateLimitInterceptor} 读取并计数。
 *
 * <p><b>阈值不写在这个注解上</b>，而是集中在 {@code repair.rate-limit.*}（见
 * {@code RateLimitRule}）：阈值是要按环境调的运行参数（演示/压测时可能临时放宽），
 * 写进注解就变成编译期常量，改一次要重新打包；而且注解上的数字散落在各个 Controller 上，
 * 想回答"这套接口限流多少"得全仓库翻。
 *
 * <p>注解上只写**档位**（{@link #key()}），具体数值仍然在配置里——这样"哪条接口用哪个档"
 * 在代码里一眼可见，而"每档多少"仍然可调。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 阈值档位，留空表示默认档（{@code repair.rate-limit.max-requests}）。
     *
     * <p>目前只有 {@code "ai"} 一档：问数每次调用都要花钱，需要比默认档严得多
     * （{@code repair.ai.rate-limit-max-requests}）。**档位名拼错不会报错、会静默回落到默认档**，
     * 所以认不出的档位在 {@code RateLimitRule} 里只回默认值，不猜。
     */
    String key() default "";
}
