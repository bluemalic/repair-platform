package com.bluemalic.repair.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 平台运营域独立进程（阶段 3.2 · ADR-024 / ADR-012）。
 *
 * <p>它只服务 {@code tenant_id = 0} 的平台运营账号：开通 / 停用学校、维护学校的统一认证配置。
 * **看不到任何租户的业务数据**——这条边界靠 {@code PlatformScopeInterceptor} 强制，
 * 不靠路由前缀（见 AGENTS §7）。
 *
 * <p>开通学校是跨库流程：建库 → 跑租户库迁移 → 落平台库的租户行 → 在那所学校自己的库里建管理员。
 * 每一步都会在失败时补偿，代码在 {@code TenantProvisionServiceImpl}。
 *
 * <p><b>为什么扫描范围写得这么细</b>：本模块依赖 {@code repair-identity-service}（建首个管理员要用
 * {@code AccountService}），若像单体那样扫 {@code com.bluemalic.repair}，就会把 identity 的
 * {@code /api/auth/**} 控制器也装配进来——一个进程只有一套端点才说得清"请求落在谁身上"。
 * 这里只扫 platform 自己的 controller、以及两个域共用的 service / common 配置。
 */
@SpringBootApplication(scanBasePackages = {
        "com.bluemalic.repair.platform",
        "com.bluemalic.repair.controller.platform",
        // identity 模块里的 service 与 sso 需要装配：AccountService / AuditService / SsoProperties
        "com.bluemalic.repair.service",
        "com.bluemalic.repair.sso",
        "com.bluemalic.repair.converter",
        // repair-common 的共享底座
        "com.bluemalic.repair.config",
        "com.bluemalic.repair.interceptor",
        "com.bluemalic.repair.tenancy"
})
public class PlatformServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformServiceApplication.class, args);
    }
}
