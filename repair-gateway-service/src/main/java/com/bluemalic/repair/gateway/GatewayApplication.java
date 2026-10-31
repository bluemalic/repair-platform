package com.bluemalic.repair.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API 网关入口（阶段 2 · ADR-024）。
 *
 * <p>职责只有三件：路由 {@code /api/**} 到后端、用 Sa-Token 校验登录态（共享 Redis）、
 * 把登录身份注入成 {@code X-User-Id / X-Tenant-Id / X-User-Type} 请求头。
 * 权限码校验仍由各服务自己做（ADR-024 决定 4：不能只信网关注入的头）。
 *
 * <p>WebFlux 应用：**不要**在 classpath 里引入 spring-webmvc（所以不依赖 repair-common）。
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
