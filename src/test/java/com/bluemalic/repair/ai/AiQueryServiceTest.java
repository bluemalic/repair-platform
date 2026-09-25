package com.bluemalic.repair.ai;

import com.bluemalic.repair.IntegrationTest;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.config.AiProperties;
import com.bluemalic.repair.service.AiQueryService;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.service.impl.AiQueryServiceImpl;
import com.bluemalic.repair.vo.AiQueryVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 问数编排（生成 → 网关 → 执行 → 结论）的集成测试。
 *
 * <p><b>模型用假实现</b>：{@link AiAssistant} 是接口，测试塞一个返回固定 SQL 的假实现进去。
 * 于是整条链路（真网关 + 真只读账号 + 真数据库）都能在 CI 上跑，**既不调模型也不花钱**；
 * 真调模型的那一层只剩提示词与异常翻译，用一次真实调用验过即可（见 PR 说明）。
 *
 * <p>为什么手工 new 服务而不是 {@code @MockBean}：{@code @MockBean} 会改测试上下文的缓存键，
 * 让 Spring 起第二个上下文，而 Sa-Token 的 {@code StpInterface} 是按上下文启动顺序写全局静态字段的——
 * 多上下文会串（{@code IntegrationTest} 的类注释里记了这个坑）。手工注入既避开它，
 * 也更直白：这个用例测的就是"这几块拼起来对不对"。
 *
 * <p>断言只用**种子数据**（`building` 表 5 行）：执行器走的是另一条连接，看不到测试事务里
 * 未提交的数据，所以不能依赖用例自己插的行——那会在本地绿、在干净库上红。
 */
@IntegrationTest
class AiQueryServiceTest {

    private static final String RO_USER = "test_ai_service";
    private static final String RO_PASSWORD = "Test@123456";
    private static final long TENANT = 1L;

    @Autowired
    private DataSource dataSource;

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    private AiQueryService service;

    private FakeAssistant assistant;

    private AiQueryExecutor executor;

    @BeforeEach
    void setUp() throws SQLException {
        AiProperties properties = new AiProperties("test-key", "http://localhost", "test-model",
                RO_USER, RO_PASSWORD, 3000, 1000, 10);
        new AiReadOnlyAccountBootstrap(dataSource, properties).ensureAccount(RO_USER, RO_PASSWORD);

        assistant = new FakeAssistant();
        executor = new AiQueryExecutor(properties, jdbcUrl);
        // 网关的三个依赖都指向**测试自己这个执行器**（用的是测试账号），不走容器里那套
        // （容器里的执行器按应用配置构建，测试环境没配 AI_DB_*，用它会直接报"未启用"）
        SqlSafetyGateway gateway = new SqlSafetyGateway(new SchemaCatalog(executor));
        service = new AiQueryServiceImpl(assistant, gateway, executor, fixedTenant());
    }

    @AfterEach
    void tearDown() throws SQLException {
        executor.destroy();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP USER IF EXISTS '" + RO_USER + "'@'%'");
        }
    }

    // ==================== 正常路径 ====================

    @Test
    void asksQuestionAndReturnsDataWithInjectedTenantCondition() {
        assistant.plan = new QueryPlan("SELECT COUNT(*) AS 楼栋数 FROM building", "none", null, "楼栋数");
        assistant.conclusion = "目前共有 5 栋楼。";

        AiQueryVO vo = service.ask("一共有几栋楼");

        assertThat(vo.getColumns()).containsExactly("楼栋数");
        assertThat(vo.getRows()).hasSize(1);
        assertThat(vo.getConclusion()).isEqualTo("目前共有 5 栋楼。");
        assertThat(vo.getChart().getType()).isEqualTo("none");
        assertThat(vo.getRowLimited()).isFalse();
        assertThat(vo.getElapsedMs()).isNotNull();
        // 返回的 SQL 是**注入过租户条件**的那条：用户看到的与实际执行的是同一条
        assertThat(vo.getSql()).contains("building.tenant_id = " + TENANT);
    }

    @Test
    void conclusionIsGroundedOnActualRows() {
        assistant.plan = new QueryPlan("SELECT name AS 楼栋 FROM building", "bar", "楼栋", null);

        service.ask("有哪些楼");

        // 模型拿到的必须是**真实查询结果**（否则它写不出"共 5 栋"这种话）。
        // 这里不断言行数：执行器走另一条连接，看到的是库里已提交的数据，而"库里有几栋楼"
        // 依赖环境（本机跑过别的验证、CI 是干净的），断言具体数字会让用例在本地红、CI 绿。
        assertThat(assistant.summarizedRows).isNotNull();
        assertThat(assistant.summarizedRows.rows()).isNotEmpty();
        assertThat(assistant.summarizedRows.columns()).containsExactly("楼栋");
    }

    // ==================== 越权：注入是 AND，只能收窄 ====================

    @Test
    void modelWrittenTenantFilterCanOnlyNarrowNeverWiden() {
        // 模型（或提示注入）试图读别家租户的数据
        assistant.plan = new QueryPlan("SELECT COUNT(*) AS 楼栋数 FROM building WHERE tenant_id = 999",
                "none", null, "楼栋数");

        AiQueryVO vo = service.ask("别的学校有几栋楼");

        // 注入的 tenant_id 是 AND 上去的 → 两个条件互相矛盾 → 空结果（而不是拿到 999 的数据）
        assertThat(vo.getRows()).hasSize(1);
        assertThat(vo.getRows().get(0).get(0).toString()).isEqualTo("0");
        assertThat(vo.getSql()).contains("tenant_id = 999").contains("building.tenant_id = " + TENANT);
    }

    // ==================== 失败路径：三种错各有各的码 ====================

    @Test
    void rejectsSqlThatFailsTheGateway() {
        assistant.plan = new QueryPlan("DELETE FROM ticket", "none", null, null);

        assertThatThrownBy(() -> service.ask("把工单都删了"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_SQL_REJECTED);
    }

    @Test
    void rejectsWhenModelProducesNoSql() {
        assistant.plan = new QueryPlan("  ", "none", null, null);

        assertThatThrownBy(() -> service.ask("随便问问"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_INTENT_UNRESOLVED);
    }

    @Test
    void reportsUnrunnableSqlAsIntentUnresolved() {
        // 过了网关但数据库不认（模型编了个字段）
        assistant.plan = new QueryPlan("SELECT no_such_column AS x FROM building", "none", null, "x");

        assertThatThrownBy(() -> service.ask("查个不存在的字段"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_INTENT_UNRESOLVED);
    }

    @Test
    void conclusionFailureDegradesInsteadOfFailingTheWholeQuery() {
        assistant.plan = new QueryPlan("SELECT COUNT(*) AS 楼栋数 FROM building", "none", null, "楼栋数");
        assistant.failConclusion = true;

        AiQueryVO vo = service.ask("一共有几栋楼");

        // 表格数据还在，只是结论退化成按行数陈述——为一句文案把整次问数判失败是本末倒置
        assertThat(vo.getRows()).hasSize(1);
        assertThat(vo.getConclusion()).isEqualTo("共 1 行结果。");
    }

    // ==================== 工具 ====================

    /** 租户从登录态取；这个用例不经过 HTTP，所以给一个固定值（值本身不影响被测逻辑）。 */
    private CurrentTenantService fixedTenant() {
        return new CurrentTenantService() {
            @Override
            public void bind(Long tenantId) {
            }

            @Override
            public Long tenantIdOrNull() {
                return TENANT;
            }

            @Override
            public Long requireTenantId() {
                return TENANT;
            }
        };
    }

    /** 假模型：按用例设定的计划作答，并把"结论时看到的数据"记下来供断言。 */
    private static class FakeAssistant implements AiAssistant {

        private QueryPlan plan;
        private String conclusion = "（假结论）";
        private boolean failConclusion;
        private AiQueryResult summarizedRows;

        @Override
        public QueryPlan plan(String question) {
            return plan;
        }

        @Override
        public String summarize(String question, AiQueryResult result) {
            if (failConclusion) {
                throw new BizException(ErrorCode.AI_MODEL_UNAVAILABLE);
            }
            this.summarizedRows = result;
            return conclusion;
        }
    }
}
