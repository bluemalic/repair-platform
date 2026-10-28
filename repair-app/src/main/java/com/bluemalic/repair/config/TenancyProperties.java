package com.bluemalic.repair.config;

import com.bluemalic.repair.tenancy.TenantSchemas;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 租户库的连接参数（阶段 6 · ADR-021）。
 *
 * <p><b>连接数是要盯着的东西</b>：部署是 2C2G 单实例，MySQL 的 `max_connections` 也不是无限的。
 * 所以租户池是**小池 + 懒建 + 空闲回收**：
 * <ul>
 *   <li>{@code pool-size} 默认 3（一所学校同时 3 个请求够用；平台库里那个主池才是 12，
 *       因为它要扛所有人的登录与后台）；</li>
 *   <li>{@code min-idle = 0}（写死在 {@code TenantDataSourceRegistry}）：没有请求时
 *       **一条连接都不占**——"空闲回收"回收的是连接，不是那个池对象（空池几乎不占内存）；</li>
 *   <li>池在第一次用到时才建：没人访问的学校不该占连接。</li>
 * </ul>
 * 于是稳态连接数 ≈ 平台库主池 + 活跃学校数 × 3 + 每校 AI 只读池 2。
 */
@Getter
@Component
public class TenancyProperties {

    /** 租户库名前缀，如 `repair_gdou` */
    private final String schemaPrefix;

    private final int poolSize;

    /** 空闲连接多久关掉（只关那些超过 min-idle 的；min-idle 是 0，所以是"全部空闲连接"） */
    private final long idleTimeoutMs;

    private final long connectionTimeoutMs;

    public TenancyProperties(@Value("${repair.tenancy.schema-prefix:repair_}") String schemaPrefix,
                             @Value("${repair.tenancy.pool-size:3}") int poolSize,
                             @Value("${repair.tenancy.idle-timeout-ms:600000}") long idleTimeoutMs,
                             @Value("${repair.tenancy.connection-timeout-ms:3000}") long connectionTimeoutMs) {
        if (poolSize < 1 || poolSize > 10) {
            // 上限 10 是为了别把主库的 max_connections 吃光：池大小 × 学校数 是能被算出来的预算
            throw new IllegalStateException("租户连接池大小必须在 1~10 之间，请检查 repair.tenancy.pool-size");
        }
        if (idleTimeoutMs < 1000) {
            throw new IllegalStateException("租户连接池的空闲回收时间必须 >= 1000 毫秒，请检查 repair.tenancy.idle-timeout-ms");
        }
        if (connectionTimeoutMs < 100) {
            throw new IllegalStateException("租户连接池的获取超时必须 >= 100 毫秒，请检查 repair.tenancy.connection-timeout-ms");
        }
        this.schemaPrefix = schemaPrefix;
        this.poolSize = poolSize;
        this.idleTimeoutMs = idleTimeoutMs;
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    /** 学校编码 → 库名（规则与校验都在 {@link TenantSchemas}，这里只做转调，避免规则漂开） */
    public String schemaFor(String tenantCode) {
        return TenantSchemas.forCode(schemaPrefix, tenantCode);
    }
}
