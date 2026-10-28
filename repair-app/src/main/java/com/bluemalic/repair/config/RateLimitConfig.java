package com.bluemalic.repair.config;

import com.bluemalic.repair.interceptor.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 限流拦截器注册。
 *
 * <p>顺序是这里唯一容易出错的地方：限流按「登录用户」计数，必须排在 Sa-Token 的登录校验
 * （{@code SaTokenConfigure} 里显式 order 0）之后，否则拿不到登录态、限流形同不存在。
 * 两处的 order 都写出来，是因为"先注册的先生效"只是默认行为，而这里依赖它——
 * 哪天有人把两个拦截器挪进同一个类，顺序就成了隐式约定。
 */
@Configuration
@RequiredArgsConstructor
public class RateLimitConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/**")
                .order(1);
    }
}
