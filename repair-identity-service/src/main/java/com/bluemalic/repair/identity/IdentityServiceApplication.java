package com.bluemalic.repair.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 身份域独立进程（阶段 3.2 · ADR-024）。
 *
 * <p><b>为什么 scanBasePackages 是 {@code com.bluemalic.repair} 而不是本包</b>：
 * 会话、租户路由、限流、数据权限这些配置类都在 {@code repair-common} 的
 * {@code com.bluemalic.repair.config} / {@code interceptor} 包里（包名保持不变是为了
 * 搬迁不产生 import 改动）。本模块的 classpath 里没有 ticket / ai 的类，
 * 所以这个大范围扫描不会把别的域的 Controller 带进来。
 *
 * <p><b>full 形态</b>：单独跑本进程，网关把 {@code /api/auth/**}、{@code /api/admin/students/**}、
 * {@code /api/admin/workers/**}、{@code /api/admin/audit-logs/**} 路由到这里。
 * <b>lite 形态</b>：单体依赖本模块的普通 jar，Bean 在单体进程里装配（同一个类，不是两份）。
 */
@SpringBootApplication(scanBasePackages = "com.bluemalic.repair")
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
