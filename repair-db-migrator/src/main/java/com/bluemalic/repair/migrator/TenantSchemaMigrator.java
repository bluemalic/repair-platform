package com.bluemalic.repair.migrator;

import com.bluemalic.repair.tenancy.TenantRoutingContext;
import com.bluemalic.repair.tenancy.TenantSchemas;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把控制库与每个租户库的迁移跑到最新（阶段 3.2）。
 *
 * <p><b>为什么要有独立作业</b>：服务拆开之后，"谁跑迁移"必须只有一个答案。
 * 三个服务各自在启动时跑 Flyway 会出现并发迁移、版本号互相等待，还会让一次发布变成
 * "哪个先起来哪个先改表"。所以 full 形态下所有服务 {@code spring.flyway.enabled=false}，
 * 发布流程先跑这个作业，再滚动重启服务。
 *
 * <p><b>可重复执行</b>：Flyway 自己按 {@code flyway_schema_history} 判断，已跑过的跳过、
 * {@code R__seed} 内容变了才重跑。作业失败直接以非 0 退出，不吞异常。
 *
 * <p><b>为什么要显式按库遍历</b>：Flyway 的连接是绑在库上的，一次只能迁移一个库；
 * 租户库清单来自平台库的 {@code tenant} 表（`docs/02` §0.2：这张表只在平台库）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantSchemaMigrator {

    private static final String CONTROL_MIGRATION_LOCATION = "classpath:db/migration";

    private static final String TENANT_MIGRATION_LOCATION = "classpath:db/tenant";

    private final JdbcTemplate jdbcTemplate;

    @Value("${spring.datasource.url}")
    private String controlUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    /** 控制库 + 所有租户库，逐库迁移；任一库失败即抛出（由入口转成非 0 退出码）。 */
    public void migrateAll() {
        migrateControl();
        List<String> schemas = tenantSchemas();
        log.info("待迁移租户库 {} 个：{}", schemas.size(), schemas);
        for (String schema : schemas) {
            migrateTenant(schema);
        }
        log.info("全部迁移完成：控制库 + {} 个租户库", schemas.size());
    }

    private void migrateControl() {
        var result = Flyway.configure()
                .dataSource(controlUrl, username, password)
                .locations(CONTROL_MIGRATION_LOCATION)
                .cleanDisabled(true)
                .load()
                .migrate();
        log.info("控制库迁移完成：执行 {} 条，目标版本 {}",
                result.migrationsExecuted, result.targetSchemaVersion);
    }

    private void migrateTenant(String schema) {
        try {
            var result = Flyway.configure()
                    .dataSource(TenantSchemas.jdbcUrl(controlUrl, schema), username, password)
                    .locations(TENANT_MIGRATION_LOCATION)
                    .cleanDisabled(true)
                    .load()
                    .migrate();
            log.info("租户库 {} 迁移完成：执行 {} 条，目标版本 {}",
                    schema, result.migrationsExecuted, result.targetSchemaVersion);
        } catch (Exception e) {
            // 带上库名再抛：一次发布里有几十个库，不知道是哪个等于没报错
            throw new IllegalStateException("租户库迁移失败：" + schema, e);
        }
    }

    /**
     * 需要迁移的租户库清单。
     *
     * <p>排除三类行：{@code db_schema} 为空（平台自身与历史残留）、
     * {@code <control>} 与其 {@code <control>:xxx} 变体（数据在控制库，没有独立库）。
     */
    private List<String> tenantSchemas() {
        List<String> all = TenantRoutingContext.callControl(() -> jdbcTemplate.queryForList(
                "SELECT db_schema FROM tenant WHERE db_schema IS NOT NULL AND deleted = 0",
                String.class));
        return all.stream()
                .filter(schema -> !TenantRoutingContext.isControlKey(schema))
                .distinct()
                .toList();
    }
}
