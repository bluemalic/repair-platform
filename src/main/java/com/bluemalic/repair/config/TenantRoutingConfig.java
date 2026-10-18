package com.bluemalic.repair.config;

import com.bluemalic.repair.interceptor.TenantRoutingInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 租户路由拦截器的注册（阶段 6 · ADR-021）。
 *
 * <p><b>它是链上第一个（order -1）</b>，因为"去哪个库"是后面每一步的前提：
 * order 0 的登录校验要查角色、order 1 的限流要读登录用户、order 2 的待改密要读 Session、
 * order 3 的平台域收口要读用户类型——其中查库的那些必须知道租户。
 *
 * <p><b>五个拦截器的 order 一起看：-1 租户路由 → 0 登录校验 → 1 限流 → 2 待改密 → 3 平台域收口。</b>
 */
@Configuration
@RequiredArgsConstructor
public class TenantRoutingConfig implements WebMvcConfigurer {

    private final TenantRoutingInterceptor tenantRoutingInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantRoutingInterceptor)
                .addPathPatterns("/**")
                .order(-1);
    }
}
