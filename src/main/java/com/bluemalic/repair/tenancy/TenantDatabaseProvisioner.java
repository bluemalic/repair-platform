package com.bluemalic.repair.tenancy;

import com.bluemalic.repair.ai.AiReadOnlyAccountBootstrap;
import com.bluemalic.repair.config.TenancyProperties;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 租户库的生命周期：**建库 → 跑租户库迁移 → 把只读账号的授权铺过去**（阶段 6 · ADR-021）。
 *
 * <p>三件事都是**幂等**的，所以调用方不需要关心"这是第一次还是第 N 次"：
 * 开通学校用它建库、启动引导用它把每个学校升级到最新结构、开通失败重试也走同一段代码。
 *
 * <p><b>为什么建库要用平台库的连接</b>：`CREATE DATABASE` 是服务器级语句，连哪个库都行，
 * 但用平台库主池（{@code controlDataSource}）有个明确的好处——**建库这件事本身属于控制面**，
 * 它不该受"当前请求是哪个租户"影响（用路由数据源的话，就看运气了）。
 *
 * <p><b>为什么库的排序规则要从平台库抄</b>：租户库的建表脚本里 `audit_log` 特意没写 COLLATE
 * （跟平台库的写法一致），它跟随**所在库的默认排序规则**。抄过来才能让两边逐列一致
 * （`TenantSchemaParityTest` 会比对这一项）。
 */
@Slf4j
@Component
public class TenantDatabaseProvisioner {

    /**
     * 租户库的迁移脚本位置。**与平台库的 `classpath:db/migration` 是两套独立历史**：
     * 每个库有自己的 `flyway_schema_history`，版本号也各排各的（ADR-021）。
     */
    static final String TENANT_MIGRATION_LOCATION = "classpath:db/tenant";

    private static final String COLLATION_SQL =
            "SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?";

    private final TenancyProperties properties;

    private final TenantSchemaCatalog catalog;

    private final AiReadOnlyAccountBootstrap aiAccountBootstrap;

    private final DataSource controlDataSource;

    private final String controlUrl;

    private final String username;

    private final String password;

    public TenantDatabaseProvisioner(TenancyProperties properties,
                                     TenantSchemaCatalog catalog,
                                     AiReadOnlyAccountBootstrap aiAccountBootstrap,
                                     @Qualifier("controlDataSource") DataSource controlDataSource,
                                     @Value("${spring.datasource.url}") String controlUrl,
                                     @Value("${spring.datasource.username}") String username,
                                     @Value("${spring.datasource.password}") String password) {
        this.properties = properties;
        this.catalog = catalog;
        this.aiAccountBootstrap = aiAccountBootstrap;
        this.controlDataSource = controlDataSource;
        this.controlUrl = controlUrl;
        this.username = username;
        this.password = password;
    }

    /** 学校编码 → 库名（规则在 {@code TenantSchemas}，这里只转调，避免两套规则漂开） */
    public String schemaFor(String tenantCode) {
        return properties.schemaFor(tenantCode);
    }

    /** 建库 + 把租户库迁移跑到最新。库已存在、迁移已跑过时都是空操作。 */
    public void createAndMigrate(String schema) {
        createDatabase(schema);
        migrate(schema);
    }

    /**
     * 把某个租户库的迁移跑到最新（幂等）。
     *
     * <p>版本迁移只补没跑过的；**可重复迁移（R__seed）在内容变化时重跑**——所以"平台新增一个权限码"
     * 会在下次启动时同步到所有学校（{@link TenantSchemaBootstrap}）。
     */
    public void migrate(String schema) {
        var result = Flyway.configure()
                .dataSource(TenantSchemas.jdbcUrl(controlUrl, schema), username, password)
                .locations(TENANT_MIGRATION_LOCATION)
                // 与平台库同一道保险：迁移脚本里不许出现 clean（AGENTS §6）
                .cleanDisabled(true)
                .load()
                .migrate();
        if (result.migrationsExecuted > 0) {
            log.info("租户库迁移完成 schema={} 执行 {} 条（脚本 {}）",
                    schema, result.migrationsExecuted, result.targetSchemaVersion);
        }
    }

    /**
     * 需要授权的库清单：**平台库 + 每个已开通的租户库**。
     *
     * <p>它读的是平台库（`tenant` 表）——而且**读不全会抛错**，调用方必须放弃这次授权：
     * 只读账号的授权是"先 REVOKE ALL 再按清单 GRANT"，拿一份不完整的清单去授权，
     * 等于把没在清单里的学校的问数权限**洗掉**。
     */
    public List<String> allDatabases() throws SQLException {
        List<String> databases = new ArrayList<>();
        databases.add(controlDatabase());
        for (String schema : catalog.provisionedSchemas().values()) {
            if (!databases.contains(schema)) {
                databases.add(schema);
            }
        }
        return databases;
    }

    /** 把只读账号的授权刷成"全部库"（幂等；没配账号则空操作；失败只记日志）。 */
    public void ensureAiReadOnlyOnAllDatabases() {
        List<String> databases;
        try {
            databases = allDatabases();
        } catch (Exception e) {
            // 宁可这一次不授权，也不能拿半份清单去 REVOKE（见 allDatabases 的说明）
            log.error("取租户库清单失败，跳过只读账号授权（AI 问数可能受影响）", e);
            return;
        }
        aiAccountBootstrap.ensureAccountOnDatabases(databases);
    }

    /** 平台库的库名（从连接上取，不让配置里重复写一遍） */
    public String controlDatabase() throws SQLException {
        try (Connection connection = controlDataSource.getConnection()) {
            return connection.getCatalog();
        }
    }

    private void createDatabase(String schema) {
        TenantSchemas.requireSafe(schema);
        try (Connection connection = controlDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            String collation = collationOf(connection, connection.getCatalog());
            // IF NOT EXISTS：让"建库"可以重跑（开通失败重试、启动引导都走这里）。
            // 万一平台外有人手工建过同名库，这里会直接用那个库——库名带 repair_ 前缀，
            // 撞上一个无关库的概率可以忽略；而幂等比"防呆"更重要。
            statement.execute("CREATE DATABASE IF NOT EXISTS `" + schema
                    + "` DEFAULT CHARACTER SET utf8mb4 COLLATE " + collation);
        } catch (SQLException e) {
            throw new IllegalStateException("创建租户库失败：" + schema + "（" + e.getMessage() + "）", e);
        }
    }

    private String collationOf(Connection connection, String database) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(COLLATION_SQL)) {
            statement.setString(1, database);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("读不到平台库的排序规则：database=" + database);
                }
                return rs.getString(1);
            }
        }
    }
}
