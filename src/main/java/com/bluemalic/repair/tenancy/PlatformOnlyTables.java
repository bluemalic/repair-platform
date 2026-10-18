package com.bluemalic.repair.tenancy;

import java.util.Set;

/**
 * **只存在于平台库**的表（阶段 6/7 · ADR-021 / ADR-022）。
 *
 * <p>这些表有一个共同点：**在知道"该去哪个租户库"之前就要读**——学校名录（登录按编码定位）、
 * SSO 配置（登录入口）、权限点（平台定义，两侧由种子保证）、Flyway 自己的账本。
 * 所以它们不进租户库、也不进搬迁计划。
 *
 * <p>三处用到这份清单，必须同源：
 * <ul>
 *   <li>`TenantSchemaParityTest`：比对两套建表脚本时排除它们；</li>
 *   <li>`TenantDataMigrationTest`：断言搬迁计划正好覆盖"平台库减去它们"；</li>
 *   <li>`docs/02` 与 `AGENTS §6` 的文字说明。</li>
 * </ul>
 */
public final class PlatformOnlyTables {

    public static final Set<String> TABLES = Set.of(
            "tenant",
            "tenant_sso_config",
            "sys_permission",
            "flyway_schema_history");

    private PlatformOnlyTables() {
    }
}
