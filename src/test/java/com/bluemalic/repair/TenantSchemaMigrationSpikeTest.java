package com.bluemalic.repair;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 6 的 spike（之一）：**同一套迁移脚本能不能对 N 个租户库各跑一遍、互不污染**。
 *
 * <p>为什么先验这一条：阶段 6 要"一个租户一个 database"，如果 Flyway 只能作用于一个 schema，
 * 或者两个库的迁移历史会互相影响，那整套方案就不成立——ADR 写得再漂亮也没用。
 *
 * <p>它验证四件事：
 * <ol>
 *   <li>两个**空库**都能从 V1 一路建到最新（含可重复执行的 R__seed），迁移条数一致</li>
 *   <li>关键表在新库里真的存在</li>
 *   <li>对已迁移的库再跑一次：没有待执行的迁移（幂等），且**不会动另一个库的历史**</li>
 *   <li>种子数据在每个库里各有一份（顺带暴露一个实现问题：`R__seed` 里含着平台与租户 1 的行，
 *       正式实现时要把"控制面"脚本与"数据面"脚本拆开——见 ADR-021）</li>
 * </ol>
 *
 * <p>**它创建 / 删除的是 `spike_tenant_*` 这两个临时库**，用完即删；不碰测试库 `repair`。
 * 需要 root 权限（CI 与本机都满足）。
 */
class TenantSchemaMigrationSpikeTest {

    private static final String HOST = env("MYSQL_HOST", "127.0.0.1");
    private static final String PORT = env("MYSQL_PORT", "13306");
    private static final String USER = env("MYSQL_USERNAME", "root");
    private static final String PASSWORD = env("MYSQL_PASSWORD", "");

    private static final String SERVER_URL = "jdbc:mysql://" + HOST + ":" + PORT
            + "/?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true&useSSL=false";
    private static final String DB_A = "spike_tenant_a";
    private static final String DB_B = "spike_tenant_b";

    @Test
    void 每个租户库都能独立跑完全部迁移() throws Exception {
        drop(DB_A);
        drop(DB_B);
        create(DB_A);
        create(DB_B);
        try {
            Flyway a = flyway(DB_A);
            Flyway b = flyway(DB_B);

            a.migrate();
            b.migrate();

            int historyA = scalar(DB_A, "SELECT COUNT(*) FROM flyway_schema_history");
            int historyB = scalar(DB_B, "SELECT COUNT(*) FROM flyway_schema_history");
            // 11 个版本脚本（V1..V11）+ 1 个可重复脚本（R__seed）
            assertThat(historyA).as("A 库的迁移条数").isEqualTo(historyB).isGreaterThanOrEqualTo(12);

            assertThat(query(DB_A, "SHOW TABLES LIKE 'ticket'")).isPositive();
            assertThat(query(DB_A, "SHOW TABLES LIKE 'knowledge_entry'")).isPositive();
            assertThat(query(DB_B, "SHOW TABLES LIKE 'worker_skill'")).isPositive();

            // 幂等：已迁移的库再跑一次不该有待执行项
            assertThat(a.info().pending()).isEmpty();

            // 独立：动 A 不影响 B 的历史
            int before = scalar(DB_B, "SELECT COUNT(*) FROM flyway_schema_history");
            a.migrate();
            assertThat(scalar(DB_B, "SELECT COUNT(*) FROM flyway_schema_history")).isEqualTo(before);

            // 种子在每个库各一份
            int buildingsA = scalar(DB_A, "SELECT COUNT(*) FROM building");
            int buildingsB = scalar(DB_B, "SELECT COUNT(*) FROM building");
            assertThat(buildingsA).isPositive().isEqualTo(buildingsB);
        } finally {
            drop(DB_A);
            drop(DB_B);
        }
    }

    // ==================== 工具 ====================

    private Flyway flyway(String database) {
        return Flyway.configure()
                .dataSource("jdbc:mysql://" + HOST + ":" + PORT + "/" + database
                        + "?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true&useSSL=false",
                        USER, PASSWORD)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load();
    }

    private void create(String database) throws Exception {
        execute("CREATE DATABASE `" + database + "` DEFAULT CHARACTER SET utf8mb4");
    }

    private void drop(String database) throws Exception {
        execute("DROP DATABASE IF EXISTS `" + database + "`");
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** 返回结果行数（用于 SHOW TABLES LIKE ...：有这一行 = 表存在） */
    private int query(String database, String sql) throws Exception {
        String url = "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true&useSSL=false";
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            int rows = 0;
            while (rs.next()) {
                rows++;
            }
            return rows;
        }
    }

    private int scalar(String database, String sql) throws Exception {
        String url = "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true&useSSL=false";
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
