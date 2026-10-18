package com.bluemalic.repair;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 两份建表脚本**不许漂开**（阶段 6 · ADR-021）。
 *
 * <p>背景：租户侧的表在过渡期里存在于两个地方——平台库 `repair`（存量租户还没搬走）与每个租户库
 * `repair_&lt;编码&gt;`。于是同一个结构变更要写两次（`db/migration/V{n}` 与 `db/tenant/T{m}`）。
 * 漏写一处是**静默**的：一个库有列、另一个没有，只有真的跑到那条 SQL 才报错，而且很可能只在一个学校身上报。
 *
 * <p>所以这里把两份脚本各跑进一个临时空库，然后逐表、逐列（含顺序、类型、可空、默认值、排序规则）、
 * 逐索引（含列顺序、唯一性、BTREE/FULLTEXT）比对，再比对平台种子数据（角色、权限、授权）的**每一行**。
 * 任何一处不一致 → 这个测试红，且在消息里直接列出差异。
 *
 * <p>比对范围 = 平台库的全部表 **减去** {@link #CONTROL_ONLY}（放行清单，目前只有 `tenant`：
 * 学校名录只在平台库，登录时还不知道该去哪个租户库，必须先按编码在平台库定位）。所以
 * "新加一张表但只写了一份脚本"会被自动抓住，不需要维护一份表名清单。
 *
 * <p>**它创建 / 删除的是 `parity_*` 两个临时库**，用完即删；不碰测试库 `repair`。
 */
class TenantSchemaParityTest {

    private static final String HOST = env("MYSQL_HOST", "127.0.0.1");
    private static final String PORT = env("MYSQL_PORT", "13306");
    private static final String USER = env("MYSQL_USERNAME", "root");
    private static final String PASSWORD = env("MYSQL_PASSWORD", "");

    private static final String SERVER_URL = url("");

    private static final String CONTROL_DB = "parity_control";
    private static final String TENANT_DB = "parity_tenant";

    /**
     * 允许只在平台库存在的表。
     *
     * <p>`tenant`（学校名录）：登录时还不知道该去哪个租户库，必须先按学校编码在平台库里查到租户行、
     * 才知道路由到哪——把这张表放进租户库等于"先猜库"，而且它一旦有两份就会有两份真相。
     */
    private static final Set<String> CONTROL_ONLY = Set.of("flyway_schema_history", "tenant");

    /** 结构比对时排除放行清单里的表（它们本来就只在平台库）——与 {@link #CONTROL_ONLY} 同一份清单 */
    private static final String CONTROL_ONLY_FILTER = " AND TABLE_NAME NOT IN ("
            + CONTROL_ONLY.stream().sorted().map(name -> "'" + name + "'")
                    .collect(java.util.stream.Collectors.joining(", "))
            + ")";

    @Test
    void 平台库与租户库的建表脚本必须逐列逐索引一致() throws Exception {
        drop(CONTROL_DB);
        drop(TENANT_DB);
        create(CONTROL_DB);
        create(TENANT_DB);
        try {
            migrate(CONTROL_DB, "classpath:db/migration");
            migrate(TENANT_DB, "classpath:db/tenant");

            // 表集合：租户库不许有 tenant；平台库的表（除放行清单）租户库都必须有。
            // flyway_schema_history 在两边都有（每个库各一份迁移历史），但它是 Flyway 的账本、
            // 不是业务结构，两张表的内容本来就不同——所以从比对集合里去掉。
            Set<String> tenantTables = tables(TENANT_DB);
            assertThat(tenantTables).as("租户库不许有 `tenant`（学校名录只在平台库）").doesNotContain("tenant");

            Set<String> controlTables = tables(CONTROL_DB);
            controlTables.removeAll(CONTROL_ONLY);
            tenantTables.removeAll(CONTROL_ONLY);
            assertThat(symmetricDifference(controlTables, tenantTables))
                    .as("两边的表集合必须一致（缺的那张表说明有一份脚本没写）")
                    .isEmpty();

            // 结构：逐列（含列顺序与排序规则）、逐索引
            List<String> columnDiff = symmetricDifference(lines(CONTROL_DB, COLUMNS_SQL), lines(TENANT_DB, COLUMNS_SQL));
            if (!columnDiff.isEmpty()) {
                fail("两边的列不一致（左=平台库独有，右=租户库独有）：\n" + String.join("\n", columnDiff));
            }
            List<String> indexDiff = symmetricDifference(lines(CONTROL_DB, INDEXES_SQL), lines(TENANT_DB, INDEXES_SQL));
            if (!indexDiff.isEmpty()) {
                fail("两边的索引不一致（左=平台库独有，右=租户库独有）：\n" + String.join("\n", indexDiff));
            }

            // 平台种子数据：角色 / 权限点 / 角色-权限授予，两边每一行都必须相同
            for (String sql : PLATFORM_DATA_SQL) {
                List<String> controlRows = lines(CONTROL_DB, sql);
                List<String> tenantRows = lines(TENANT_DB, sql);
                assertThat(tenantRows)
                        .as("平台种子数据不一致：%s（两份 R__seed 必须同源）", sql)
                        .containsExactlyElementsOf(controlRows);
                assertThat(tenantRows).as("种子数据不该是空的：%s", sql).isNotEmpty();
            }
        } finally {
            drop(CONTROL_DB);
            drop(TENANT_DB);
        }
    }

    /**
     * 结构一致还不够，**索引得真的能用**：在租户库里插一条知识库条目，用中文短语跑一次
     * `MATCH ... AGAINST`，必须命中。这条挡的是"表建出来了但全文索引没建（或少写了 WITH PARSER ngram）"——
     * 那种情况下结构比对可能过（INDEX_TYPE=FULLTEXT 两边都有），但检索是坏的。
     */
    @Test
    void 租户库的ngram全文索引在新库里可用() throws Exception {
        drop(TENANT_DB);
        create(TENANT_DB);
        try {
            migrate(TENANT_DB, "classpath:db/tenant");
            execute(TENANT_DB, "INSERT INTO knowledge_entry (id, tenant_id, category_id, title, symptom, solution, tags)"
                    + " VALUES (1, 1, NULL, '门锁转不动', '宿舍门锁转不动，钥匙插进去卡住', '先推一下门再试', '门锁 卡住 钥匙 转不动')");
            int hits = scalar(TENANT_DB, "SELECT COUNT(*) FROM knowledge_entry"
                    + " WHERE MATCH(title, symptom, tags) AGAINST('宿舍门锁转不动') > 0 AND tenant_id = 1");
            assertThat(hits).as("新租户库的 ngram 全文索引应当能命中中文短语").isEqualTo(1);
        } finally {
            drop(TENANT_DB);
        }
    }

    // ==================== 比对用的查询 ====================

    /** 逐列：表、列、列顺序、类型、可空、默认值、扩展（auto_increment / on update）、排序规则 */
    private static final String COLUMNS_SQL = """
            SELECT CONCAT(TABLE_NAME, '.', ORDINAL_POSITION, ' ', COLUMN_NAME,
                          ' ', COLUMN_TYPE,
                          ' null=', IS_NULLABLE,
                          ' default=', IFNULL(COLUMN_DEFAULT, '<null>'),
                          ' extra=', EXTRA,
                          ' collation=', IFNULL(COLLATION_NAME, '<none>')) AS line
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ?""" + CONTROL_ONLY_FILTER + """
            ORDER BY TABLE_NAME, ORDINAL_POSITION
            """;

    /** 逐索引：表、索引、列顺序、唯一性、类型（BTREE / FULLTEXT） */
    private static final String INDEXES_SQL = """
            SELECT CONCAT(TABLE_NAME, '.', INDEX_NAME, '[', SEQ_IN_INDEX, ']',
                          ' col=', COLUMN_NAME,
                          ' unique=', IF(NON_UNIQUE = 0, 'Y', 'N'),
                          ' type=', INDEX_TYPE) AS line
            FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ?""" + CONTROL_ONLY_FILTER + """
            ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
            """;

    /**
     * 平台定义的数据（两边必须逐行相同）。
     *
     * <p>为什么连主键 id 一起比：两份文件用的是同一套固定 id（1..24 权限、1..4 角色、授予行的 id 也固定），
     * 只比内容的话，"同一份授权在两边 id 不同"这种漂开就漏掉了——而 id 漂开会让
     * `sys_role_permission` 的重复插入判断失效。
     */
    private static final List<String> PLATFORM_DATA_SQL = List.of(
            "SELECT CONCAT_WS('|', id, tenant_id, code, name, description, status, deleted) FROM sys_role ORDER BY id",
            "SELECT CONCAT_WS('|', id, code, name, type, parent_id, sort, status) FROM sys_permission ORDER BY id",
            "SELECT CONCAT_WS('|', id, role_id, permission_id) FROM sys_role_permission ORDER BY id");

    // ==================== 工具 ====================

    private void migrate(String database, String location) {
        Flyway.configure()
                .dataSource(url(database), USER, PASSWORD)
                .locations(location)
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    private Set<String> tables(String database) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        try (Connection connection = connection(database);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'")) {
            statement.setString(1, database);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    /** 执行一条查询并把第一列拼成列表；SQL 允许带 0 或 1 个占位符（1 个时绑定库名，用于查 information_schema） */
    private List<String> lines(String database, String sql) throws Exception {
        List<String> result = new ArrayList<>();
        try (Connection connection = connection(database);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (statement.getParameterMetaData().getParameterCount() == 1) {
                statement.setString(1, database);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
        }
        return result;
    }

    private int scalar(String database, String sql) throws Exception {
        try (Connection connection = connection(database);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void execute(String database, String sql) throws Exception {
        try (Connection connection = connection(database);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void create(String database) throws Exception {
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + database + "` DEFAULT CHARACTER SET utf8mb4");
        }
    }

    private void drop(String database) throws Exception {
        try (Connection connection = DriverManager.getConnection(SERVER_URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    private Connection connection(String database) throws Exception {
        return DriverManager.getConnection(url(database), USER, PASSWORD);
    }

    /** 对称差集：只在一侧出现的元素（保持顺序便于阅读） */
    private static List<String> symmetricDifference(Set<String> left, Set<String> right) {
        List<String> diff = new ArrayList<>();
        for (String item : left) {
            if (!right.contains(item)) {
                diff.add("平台库独有: " + item);
            }
        }
        for (String item : right) {
            if (!left.contains(item)) {
                diff.add("租户库独有: " + item);
            }
        }
        return diff;
    }

    private static List<String> symmetricDifference(List<String> left, List<String> right) {
        return symmetricDifference(new TreeSet<>(left), new TreeSet<>(right));
    }

    private static String url(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true&useSSL=false";
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
