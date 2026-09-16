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
 * <p>将来某条接口需要独立阈值时，在这里加一个 {@code key()} 属性、并在 {@code RateLimitRule}
 * 里加对应配置——现在的计数键已经是「用户 + 接口方法」，加维度不会影响已有接口。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
}
