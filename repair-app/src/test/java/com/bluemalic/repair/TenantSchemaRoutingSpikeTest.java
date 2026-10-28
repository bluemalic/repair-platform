package com.bluemalic.repair;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段 6 的 spike（之二）：**每租户一个连接池时，按请求切换会不会串库；配错的租户是报错还是静默读别家数据**。
 *
 * <p>为什么必须验：MySQL 没有 PostgreSQL 的 `search_path`，用"共享连接池 + `USE 库名`"的写法，
 * 连接还回池里时仍指向上一个库——下一个借到它的人就读到别人的数据，而且**不会报错**。
 * 这里的结论决定 ADR 里的路由方案：
 *
 * <ul>
 *   <li>每个租户独立池（本例每个池只给 1 条连接，**故意逼它复用**）：A → B → A 依次切换，
 *       每次读到的都是自己的数据；</li>
 *   <li>{@code lenientFallback = false}（fail-closed）：未知租户直接抛异常，不会回落到默认库；</li>
 *   <li>反例：默认的 {@code lenientFallback = true} 会把未知租户**静默**路由到默认数据源——
 *       这就是"配错了也不报错、只是读到别家数据"的形态，必须在实现里关掉。</li>
 * </ul>
 *
 * <p>**它创建 / 删除的是 `spike_route_*` 临时库**，用完即删；不碰测试库。
 */
class TenantSchemaRoutingSpikeTest {

    private static final String HOST = env("MYSQL_HOST", "127.0.0.1");
    private static final String PORT = env("MYSQL_PORT", "13306");
    private static final String USER = env("MYSQL_USERNAME", "root");
    private static final String PASSWORD = env("MYSQL_PASSWORD", "");

    private static final String SERVER_URL = "jdbc:mysql://" + HOST + ":" + PORT
            + "/?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true&useSSL=false";
    private static final String DB_A = "spike_route_a";
    private static final String DB_B = "spike_route_b";

    /** 模拟"当前请求属于哪个租户"——实现里就是登录态里的 tenantId */
    private static final ThreadLocal<Long> CURRENT_TENANT = new ThreadLocal<>();

    @Test
    void 租户切换不串库_未知租户在failClosed下直接报错() throws Exception {
        prepareDatabase(DB_A, "租户A的数据");
        prepareDatabase(DB_B, "租户B的数据");
        HikariDataSource poolA = pool(DB_A);
        HikariDataSource poolB = pool(DB_B);
        try {
            DataSource routing = routingDataSource(poolA, poolB, false);

            assertThat(nameOf(routing, 1L)).isEqualTo("租户A的数据");
            assertThat(nameOf(routing, 2L)).isEqualTo("租户B的数据");
            // 回到 A：连接池里那条连接被复用，仍必须是 A 的数据（不串库）
            assertThat(nameOf(routing, 1L)).isEqualTo("租户A的数据");

            // fail-closed：未知租户必须报错，不能回落到默认库
            assertThatThrownBy(() -> nameOf(routing, 99L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot determine target DataSource");
        } finally {
            CURRENT_TENANT.remove();
            poolA.close();
            poolB.close();
            drop(DB_A);
            drop(DB_B);
        }
    }

    /** 反例：默认的 lenientFallback=true 会静默回落到默认库——这是绝对不能留的配置。 */
    @Test
    void 默认的lenientFallback会把未知租户静默路由到默认库() throws Exception {
        prepareDatabase(DB_A, "租户A的数据");
        prepareDatabase(DB_B, "租户B的数据");
        HikariDataSource poolA = pool(DB_A);
        HikariDataSource poolB = pool(DB_B);
        try {
            DataSource routing = routingDataSource(poolA, poolB, true);
            // 未知租户 99 不报错，而是读到了默认库（A）的数据
            assertThat(nameOf(routing, 99L)).isEqualTo("租户A的数据");
        } finally {
            CURRENT_TENANT.remove();
            poolA.close();
            poolB.close();
            drop(DB_A);
            drop(DB_B);
        }
    }

    // ==================== 工具 ====================

    private DataSource routingDataSource(HikariDataSource poolA, HikariDataSource poolB,
                                         boolean lenientFallback) {
        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return CURRENT_TENANT.get();
            }
        };
        Map<Object, Object> targets = new HashMap<>();
        targets.put(1L, poolA);
        targets.put(2L, poolB);
        routing.setTargetDataSources(targets);
        // 默认库也设上：反例用例要证明"未知租户会掉到它这里"
        routing.setDefaultTargetDataSource(poolA);
        routing.setLenientFallback(lenientFallback);
        routing.afterPropertiesSet();
        return routing;
    }

    private String nameOf(DataSource routing, long tenantId) throws Exception {
        CURRENT_TENANT.set(tenantId);
        try (Connection connection = routing.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT name FROM probe WHERE id = 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private HikariDataSource pool(String database) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:mysql://" + HOST + ":" + PORT + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true&useSSL=false");
        dataSource.setUsername(USER);
        dataSource.setPassword(PASSWORD);
        // **故意只给 1 条连接**：逼两个请求复用同一条物理连接，把"连接没切干净"的问题放大
        dataSource.setMaximumPoolSize(1);
        dataSource.setPoolName("spike-" + database);
        return dataSource;
    }

    private void prepareDatabase(String database, String marker) throws Exception {
        execute("DROP DATABASE IF EXISTS `" + database + "`");
        execute("CREATE DATABASE `" + database + "` DEFAULT CHARACTER SET utf8mb4");
        String url = "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true&useSSL=false";
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE probe (id INT PRIMARY KEY, name VARCHAR(32))");
            statement.execute("INSERT INTO probe (id, name) VALUES (1, '" + marker + "')");
        }
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

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
