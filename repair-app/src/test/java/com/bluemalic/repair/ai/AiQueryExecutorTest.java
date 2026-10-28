package com.bluemalic.repair.ai;

import com.bluemalic.repair.IntegrationTest;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.config.AiProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 只读账号（ADR-003 第 1 层）与查询执行器（第 4 层）的集成测试——**用真库、真账号**。
 *
 * <p>为什么第 1 层必须实测而不是"看代码知道它会 GRANT SELECT"：它的全部价值就是
 * **数据库层面真的改不了数据**。这一条只有连上去试一条 {@code DELETE} 才知道，
 * 而且它依赖 GRANT 语句拼得对、账号匹配得上、MySQL 版本行为一致——全是纸面看不出来的。
 *
 * <p>⚠️ 这个用例会执行 DDL（{@code CREATE USER}），而 **DDL 在 MySQL 里会隐式提交**，
 * 所以它不参与测试事务的回滚：账号在 {@link #tearDown} 里显式删掉。用独立的账号名
 * （{@code test_ai_readonly}）而不是配置里那个，避免和真实配置互相干扰。
 *
 * <p>阈值故意调小（1 秒超时、5 行上限）让用例跑得快：超时用 {@code SELECT SLEEP}、
 * 截断用 {@code UNION ALL} 造行，都不依赖库里有数据（测试事务回滚后表是空的）。
 */
@IntegrationTest
class AiQueryExecutorTest {

    private static final String RO_USER = "test_ai_readonly";
    private static final String RO_PASSWORD = "Test@123456";
    private static final int MAX_ROWS = 5;

    @Autowired
    private DataSource dataSource;

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    private AiProperties properties;
    private AiReadOnlyAccountBootstrap bootstrap;
    private AiQueryExecutor executor;

    @BeforeEach
    void setUp() {
        // 超时 1 秒、上限 5 行：让超时与截断的用例跑得快
        properties = new AiProperties("", "http://localhost", "test-model",
                RO_USER, RO_PASSWORD, 1000, MAX_ROWS, 10);
        bootstrap = new AiReadOnlyAccountBootstrap(dataSource, properties);
        executor = new AiQueryExecutor(properties, jdbcUrl);
    }

    @AfterEach
    void tearDown() throws SQLException {
        executor.destroy();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP USER IF EXISTS '" + RO_USER + "'@'%'");
        }
    }

    // ==================== 第 1 层：只读账号 ====================

    @Test
    void bootstrapCreatesAccountThatCanReadWhitelistAndNothingElse() throws Exception {
        bootstrap.ensureAccount(RO_USER, RO_PASSWORD);
        // 幂等：再跑一次不该报错（每次启动都会跑，这是它必须成立的前提）
        bootstrap.ensureAccount(RO_USER, RO_PASSWORD);

        // ① 白名单表能读
        assertThat(countAsReadOnly("SELECT COUNT(*) FROM ticket")).isNotNull();

        // ② **不能改数据** —— 第 1 层的全部意义。只读账号挡不住越权读（那是第 3 层的事），
        //    但它必须挡住写：DELETE 会被 MySQL 拒绝（1142）
        assertThatThrownBy(() -> executeAsReadOnly("DELETE FROM ticket"))
                .as("只读账号不该能删数据")
                .isInstanceOf(SQLException.class);

        // ③ 白名单外的表读不了（授权只到白名单那几张，不是整个库）
        assertThatThrownBy(() -> countAsReadOnly("SELECT COUNT(*) FROM tenant"))
                .as("白名单外的表不该可读")
                .isInstanceOf(SQLException.class);
    }

    @Test
    void bootstrapSyncsPasswordOnEveryRun() throws Exception {
        bootstrap.ensureAccount(RO_USER, RO_PASSWORD);
        // 口令改了：再引导一次就该生效（与平台账号"只建不改"相反——这个是应用自己要用的凭据）
        bootstrap.ensureAccount(RO_USER, "Another@123456");

        assertThatThrownBy(() -> countAsReadOnly("SELECT COUNT(*) FROM ticket"))
                .as("旧口令应已失效")
                .isInstanceOf(SQLException.class);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, RO_USER, "Another@123456")) {
            assertThat(connection.isValid(2)).isTrue();
        }
    }

    // ==================== 第 4 层：执行、行数上限、超时 ====================

    @Test
    void executorReturnsColumnLabelsAndNumbers() throws Exception {
        bootstrap.ensureAccount(RO_USER, RO_PASSWORD);

        AiQueryResult result = executor.execute("SELECT 1 AS 报修量, '水电' AS 类别");

        assertThat(result.columns()).containsExactly("报修量", "类别");
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rowLimited()).isFalse();
        // 数字必须是数字（BigDecimal）：全局规则会把 Long 序列化成字符串，那样图就画不出来了
        assertThat(result.rows().get(0).get(0)).isInstanceOf(java.math.BigDecimal.class);
        assertThat(result.rows().get(0).get(1)).isEqualTo("水电");
    }

    @Test
    void executorTruncatesAtMaxRowsAndFlagsIt() throws Exception {
        bootstrap.ensureAccount(RO_USER, RO_PASSWORD);

        // 造 7 行（比上限 5 多），不依赖库里有数据
        AiQueryResult result = executor.execute("""
                SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7""");

        assertThat(result.rows()).hasSize(MAX_ROWS);
        assertThat(result.rowLimited()).as("超过上限时应标记截断").isTrue();
    }

    @Test
    void executorTimesOutSlowQuery() throws Exception {
        bootstrap.ensureAccount(RO_USER, RO_PASSWORD);

        assertThatThrownBy(() -> executor.execute("SELECT SLEEP(4)"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("缩小查询范围")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_QUERY_TIMEOUT);
    }

    @Test
    void executorReportsUnrunnableSqlAsIntentUnresolved() throws Exception {
        bootstrap.ensureAccount(RO_USER, RO_PASSWORD);

        // 模型编了个不存在的字段：这不是"越权"也不是"超时"，而是"这条问题没变成能跑的查询"
        assertThatThrownBy(() -> executor.execute("SELECT no_such_column FROM ticket"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_INTENT_UNRESOLVED);
    }

    @Test
    void executorRefusesToRunWithoutReadOnlyAccount() {
        AiProperties notConfigured = new AiProperties("", "http://localhost", "test-model",
                "", "", 1000, MAX_ROWS, 10);
        AiQueryExecutor withoutAccount = new AiQueryExecutor(notConfigured, jdbcUrl);

        assertThatThrownBy(() -> withoutAccount.execute("SELECT 1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未启用")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_MODEL_UNAVAILABLE);
    }

    // ==================== 工具：用只读账号直连（绕过执行器，单独验证权限） ====================

    private Object countAsReadOnly(String sql) throws SQLException {
        return executeAsReadOnly(sql);
    }

    private Object executeAsReadOnly(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, RO_USER, RO_PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getObject(1) : null;
        }
    }
}
