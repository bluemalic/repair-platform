package com.bluemalic.repair.tenancy;

import com.bluemalic.repair.config.TenancyProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户库的连接池注册表：**每个学校一个池，用到才建**（阶段 6 · ADR-021）。
 *
 * <p>为什么必须是"每租户一个池"而不是"一个池 + 每次 `USE 库名`"：MySQL 没有恢复会话默认库的机制，
 * 连接还回池里时仍指向上一个库，下一个借到它的人读到的是别人的数据且**不会报错**（spike 实测，ADR-021）。
 * 池是隔离的物理边界，路由键选的正是"池"。
 *
 * <p><b>懒建</b>：`ConcurrentHashMap.computeIfAbsent`，第一次用到某个学校时才建它的池。
 * Hikari 也是懒连的（池建出来时是 0 条连接），所以"开通了但没人用的学校"不占连接。
 *
 * <p><b>空闲回收</b>：`minimumIdle = 0` + `idleTimeout` → 一段时间没人用，连接全部还回 MySQL。
 * 回收的是**连接**（真正的稀缺资源），池对象本身（一个 Map 项 + 配置）留着几乎不占内存——
 * 所以没有做"连池一起关掉"的定时任务：那会引入一个调度线程和一套额外状态，换来的收益可以忽略。
 *
 * <p>连接串由平台库的连接串推出来（{@link TenantSchemas#jdbcUrl}），账号口令与平台库相同：
 * **每租户一个数据库账号**是计划中的第二道防线，但它需要一套凭据保管机制（口令存哪、
 * 怎么轮换），本阶段不做——见 ADR-021 「落地时的修正」里写明的那条推迟。
 */
@Slf4j
@Component
public class TenantDataSourceRegistry implements DisposableBean {

    private static final int MIN_IDLE = 0;

    private final TenancyProperties properties;

    private final String controlUrl;

    private final String username;

    private final String password;

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public TenantDataSourceRegistry(TenancyProperties properties,
                                    @Value("${spring.datasource.url}") String controlUrl,
                                    @Value("${spring.datasource.username}") String username,
                                    @Value("${spring.datasource.password}") String password) {
        this.properties = properties;
        this.controlUrl = controlUrl;
        this.username = username;
        this.password = password;
    }

    /**
     * 取某个租户库的连接池；没有就建一个。
     *
     * <p>库名一定是 {@link TenantSchemas#requireSafe} 允许的形状（它由 `tenant.db_schema` 而来，
     * 写进去的时候校验过），所以这里不做二次校验——但**校验失败会抛错而不是静默连平台库**，
     * 这是这条链路唯一需要保证的性质。
     */
    public HikariDataSource dataSourceFor(String schema) {
        return pools.computeIfAbsent(schema, this::createPool);
    }

    /** 已经建过池的库（诊断/测试用；不代表所有已开通的学校） */
    public Set<String> pooledSchemas() {
        return Set.copyOf(pools.keySet());
    }

    private HikariDataSource createPool(String schema) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("TenantPool-" + schema);
        config.setJdbcUrl(TenantSchemas.jdbcUrl(controlUrl, schema));
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(properties.getPoolSize());
        config.setMinimumIdle(MIN_IDLE);
        config.setIdleTimeout(properties.getIdleTimeoutMs());
        config.setConnectionTimeout(properties.getConnectionTimeoutMs());
        // 与平台库主池同一套：连接最长活 30 分钟，让 MySQL 侧的 wait_timeout / 重启都能被自然吸收
        config.setMaxLifetime(1800000L);
        log.info("建立租户库连接池 schema={} 最大连接={} 空闲回收={}ms",
                schema, properties.getPoolSize(), properties.getIdleTimeoutMs());
        return new HikariDataSource(config);
    }

    /** 关服时把所有租户池关掉（Hikari 的 close 会等待在用的连接归还） */
    @Override
    public void destroy() {
        pools.forEach((schema, pool) -> {
            try {
                pool.close();
            } catch (Exception e) {
                log.warn("关闭租户连接池失败 schema={} err={}", schema, e.getMessage());
            }
        });
    }
}
