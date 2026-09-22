package com.bluemalic.repair.config;

import com.bluemalic.repair.interceptor.MustChangePasswordInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 首次登录强制改密的拦截器注册。
 *
 * <p>顺序排在最后（order 2）：它要读 Sa-Token Session 里的标记，必须先有登录态
 * （{@code SaTokenConfigure} 的 order 0）；限流是 order 1，排在它前面问题也不大——
 * 待改密的账号照样会被限流挡住，这没有坏处。
 *
 * <p>三个拦截器的 order 一起看：0 登录校验 → 1 限流 → 2 待改密。改动任何一个都要回头对一遍。
 */
@Configuration
@RequiredArgsConstructor
public class MustChangePasswordConfig implements WebMvcConfigurer {

    private final MustChangePasswordInterceptor mustChangePasswordInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(mustChangePasswordInterceptor)
                .addPathPatterns("/**")
                .order(2);
    }
}
