package com.bluemalic.repair.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截器注册。
 *
 * <p><b>这个类不写，注解鉴权就是死的。</b> Sa-Token 1.46 没有独立的注解拦截器，
 * 注解校验由 {@link SaInterceptor} 一并负责（它的 {@code isAnnotation} 默认为 true）。
 * 所以注册一次，两层校验同时生效：
 *
 * <ol>
 *   <li>全局登录校验 —— 下面 {@code SaRouter} 里的那条规则</li>
 *   <li>方法级注解校验 —— Controller 上的 {@code @SaCheckPermission}</li>
 * </ol>
 *
 * <p>白名单只需要放登录接口：拦截器只匹配 {@code /api/**}，所以 {@code /doc.html}、
 * {@code /v3/api-docs}、{@code /actuator/health} 天然不受影响。
 *
 * <p><b>order 0 是显式写的</b>：限流拦截器（{@code RateLimitConfig}）依赖"登录校验先跑"，
 * 它排 order 1，且按登录用户计数。两个数字要一起看，别单独改。
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> SaRouter
                        .match("/api/**")
                        // 登录接口本身当然不能要求已登录
                        .notMatch("/api/auth/login")
                        .check(r -> StpUtil.checkLogin())))
                .addPathPatterns("/**")
                .order(0);
    }
}
