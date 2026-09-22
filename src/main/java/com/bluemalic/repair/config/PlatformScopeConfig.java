package com.bluemalic.repair.config;

import com.bluemalic.repair.interceptor.PlatformScopeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 平台域收口拦截器的注册。
 *
 * <p>顺序排在最后（order 3）：它要读 Sa-Token Session 里的用户类型，必须先有登录态（order 0）。
 * 排在待改密（order 2）之后也是对的——平台账号首次登录同样要先改掉初始口令。
 *
 * <p><b>四个拦截器的 order 一起看：0 登录校验 → 1 限流 → 2 待改密 → 3 平台域收口。</b>
 * 改动任何一个都要回头对一遍这个顺序。
 */
@Configuration
@RequiredArgsConstructor
public class PlatformScopeConfig implements WebMvcConfigurer {

    private final PlatformScopeInterceptor platformScopeInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(platformScopeInterceptor)
                .addPathPatterns("/**")
                .order(3);
    }
}
