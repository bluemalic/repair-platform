package com.bluemalic.repair.gateway.config;

import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关的登录校验（Sa-Token WebFlux 版）。
 *
 * <p>白名单与 {@code repair-app} 的 {@code SaTokenConfigure} 保持一致：账号密码登录、统一认证
 * 三个入口、Mock IdP。网关只认登录态；**权限码不在这里判**——各服务自己的
 * {@code @SaCheckPermission} 仍然生效（ADR-024 决定 4）。
 *
 * <p>401 返回体与单体 {@code Result} 结构对齐（code/message/data/traceId），错误码用
 * {@code ErrorCode.NOT_LOGIN=10002}。网关不依赖 common（会带进 spring-webmvc），所以这里是
 * 一份显式常量；两边对不上时以 {@code ErrorCode} 为准。
 */
@Configuration
public class SaTokenGatewayConfig {

    static final String NOT_LOGIN_JSON =
            "{\"code\":10002,\"message\":\"未登录或登录已过期\",\"data\":null,\"traceId\":\"\"}";

    @Bean
    public SaReactorFilter saReactorFilter() {
        return new SaReactorFilter()
                .addInclude("/api/**")
                .addExclude("/api/auth/login", "/api/auth/sso/**", "/api/mock-idp/**")
                .setAuth(obj -> StpUtil.checkLogin())
                .setError(e -> NOT_LOGIN_JSON);
    }
}
