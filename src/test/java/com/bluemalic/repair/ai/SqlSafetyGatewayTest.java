package com.bluemalic.repair.ai;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL 安全网关的单测——**这个类不需要模型、不需要数据库、也不起 Spring 上下文**。
 *
 * <p>这是 ADR-003 那套设计最值钱的地方：安全逻辑被抽成了纯函数，所以它可以被穷举地测
 * （照 {@code TicketDataScopeHandlerTest} 的范式）。如果安全校验和"调模型"缠在一起，
 * 这些用例要么跑不起来、要么每次都要花钱。
 *
 * <p>用例分两组：**通过组**盯的是"注入对不对"（尤其是有 OR 的 WHERE 必须加括号那一条），
 * **拒绝组**盯的是"该拦的都拦住了"。拒绝组的每一条都对应一个真实的绕过思路。
 */
class SqlSafetyGatewayTest {

    private static final long TENANT = 7L;

    /** 默认：表都没有 deleted 列（于是注入的只有租户条件，断言里只出现租户条件）。 */
    private final SqlSafetyGateway gateway = new SqlSafetyGateway((table, column) -> false);

    /** 有逻辑删除列的情形，单独几条用例（见下方"逻辑删除"一节）。 */
    private final SqlSafetyGateway gatewayWithLogicDelete = new SqlSafetyGateway(
            (table, column) -> "deleted".equals(column));

    // ==================== 通过：注入租户条件 ====================

    @Test
    void singleTableInjectsTenant() {
        SqlSafetyResult result = gateway.validateAndScope("SELECT id FROM ticket", TENANT);

        assertThat(result.sql()).isEqualTo("SELECT id FROM ticket WHERE ticket.tenant_id = 7");
        assertThat(result.tables()).containsExactly("ticket");
    }

    @Test
    void everyJoinedTableGetsTenantCondition() {
        SqlSafetyResult result = gateway.validateAndScope(
                "SELECT b.name, COUNT(*) AS cnt FROM ticket t JOIN building b ON t.building_id = b.id GROUP BY b.name",
                TENANT);

        // 两张表各自被限定：只限定 ticket 的话，building 会跨租户（这正是不能只靠 MyBatis 拦截器的原因）
        assertThat(result.sql()).contains("t.tenant_id = 7").contains("b.tenant_id = 7");
        assertThat(result.tables()).containsExactlyInAnyOrder("ticket", "building");
    }

    @Test
    void aliasIsUsedAsQualifier() {
        SqlSafetyResult result = gateway.validateAndScope("SELECT t.id FROM ticket t", TENANT);

        assertThat(result.sql()).contains("t.tenant_id = 7");
        assertThat(result.sql()).doesNotContain("ticket.tenant_id");
    }

    @Test
    void existingWhereIsAnded() {
        SqlSafetyResult result = gateway.validateAndScope("SELECT id FROM ticket WHERE status = 50", TENANT);

        assertThat(result.sql()).contains("status = 50").contains("ticket.tenant_id = 7");
    }

    /**
     * **拼接条件最容易踩的坑**：不給原有 WHERE 加括号的话，{@code a OR b} 会渲染成
     * {@code a OR b AND tenant}，按优先级读成 {@code a OR (b AND tenant)} —— 语义被悄悄改成"多返回数据"。
     */
    @Test
    void orWhereIsParenthesizedSoSemanticsAreKept() {
        SqlSafetyResult result = gateway.validateAndScope(
                "SELECT id FROM ticket WHERE status = 50 OR status = 60", TENANT);

        assertThat(result.sql()).contains("(status = 50 OR status = 60) AND ticket.tenant_id = 7");
    }

    /**
     * 模型自己写了 {@code tenant_id}：注入是 AND 上去的，所以**只能收窄、不能放宽**。
     * 想查别家租户的数据，结果是空集，而不是拿到别人的数据。
     */
    @Test
    void modelWrittenTenantFilterCanOnlyNarrow() {
        SqlSafetyResult result = gateway.validateAndScope(
                "SELECT id FROM ticket WHERE tenant_id = 999", TENANT);

        assertThat(result.sql()).contains("tenant_id = 999").contains("ticket.tenant_id = 7");
    }

    /**
     * JOIN 是跨表查询的**唯一**形态：两张表都在外层 FROM/JOIN 上，各自被注入租户条件。
     * （子查询整体不允许，见拒绝组的 {@code rejectsSubqueryAnywhere}——那是
     * "还有多少工单没有评价"这类问题的标准写法，模型很容易写成 {@code NOT IN (SELECT ...)}。）
     */
    @Test
    void joinsGetTenantConditionOnEveryTable() {
        SqlSafetyResult result = gateway.validateAndScope(
                "SELECT building.name, COUNT(*) FROM ticket "
                        + "JOIN building ON ticket.building_id = building.id GROUP BY building.name",
                TENANT);

        assertThat(result.sql()).contains("ticket.tenant_id = 7").contains("building.tenant_id = 7");
        assertThat(result.tables()).containsExactlyInAnyOrder("ticket", "building");
    }

    /**
     * <b>LEFT JOIN 的条件必须注到 ON 上，不能注到 WHERE 上</b>——这是评测集发现的第二个坑
     * （docs/07 记了整条链路）：反连接写法 {@code LEFT JOIN e ON ... WHERE e.id IS NULL} 里，
     * 如果 {@code e.tenant_id = 7} 被放进 WHERE，"没匹配上"的行（{@code e.*} 全是 NULL）
     * 会被 {code NULL = 7} 判假而滤掉，LEFT JOIN 悄悄退化成 INNER JOIN。
     * 实测："还有多少工单没有评价"的答案从 6 变成 0，**而且是安静地错**。
     */
    @Test
    void leftJoinConditionGoesIntoOnClause() {
        SqlSafetyResult result = gateway.validateAndScope(
                "SELECT ticket.id FROM ticket LEFT JOIN ticket_evaluation "
                        + "ON ticket.id = ticket_evaluation.ticket_id WHERE ticket_evaluation.id IS NULL",
                TENANT);

        // 右表（ticket_evaluation）的条件在 ON 里 —— 落在 WHERE 之前；左表的条件仍在 WHERE 里
        String sql = compact(result.sql());
        assertThat(sql).contains("LEFT JOIN ticket_evaluation ON (ticket.id = ticket_evaluation.ticket_id)"
                + " AND ticket_evaluation.tenant_id = 7");
        assertThat(sql.indexOf("ticket_evaluation.tenant_id = 7")).isLessThan(sql.indexOf("WHERE"));
        assertThat(sql.indexOf("ticket.tenant_id = 7")).isGreaterThan(sql.indexOf("WHERE"));
    }

    /** 原来 ON 里的 OR 也要加括号，否则条件会从"收窄"变成"放宽"。 */
    @Test
    void orInsideOnClauseIsParenthesizedSoSemanticsAreKept() {
        SqlSafetyResult result = gateway.validateAndScope(
                "SELECT ticket.id FROM ticket LEFT JOIN building "
                        + "ON ticket.building_id = building.id OR building.id = 1",
                TENANT);

        assertThat(result.sql()).contains(
                "ON (ticket.building_id = building.id OR building.id = 1) AND building.tenant_id = 7");
    }

    /** 逻辑删除列同样要进 ON：它是同一条理由（右表被过滤掉，反连接就失效）。 */
    @Test
    void leftJoinAlsoGetsLogicDeleteConditionInOnClause() {
        SqlSafetyResult result = gatewayWithLogicDelete.validateAndScope(
                "SELECT ticket.id FROM ticket LEFT JOIN ticket_evaluation "
                        + "ON ticket.id = ticket_evaluation.ticket_id",
                TENANT);

        assertThat(result.sql()).contains("ticket_evaluation.tenant_id = 7")
                .contains("ticket_evaluation.deleted = 0")
                .contains("ticket.tenant_id = 7");
    }

    @Test
    void markdownFenceIsStripped() {
        SqlSafetyResult result = gateway.validateAndScope("```sql\nSELECT id FROM ticket\n```", TENANT);

        assertThat(result.sql()).isEqualTo("SELECT id FROM ticket WHERE ticket.tenant_id = 7");
    }

    // ==================== 逻辑删除：补上 MyBatis-Plus 会自动补的那个条件 ====================

    /**
     * AI 的 SQL 走裸 JDBC，**绕过了 MyBatis-Plus 的逻辑删除过滤**——不补 {@code deleted = 0}，
     * 它就会把已删除的楼栋、已删除的工单一起算进去，于是"AI 说 6 栋楼、界面显示 5 栋"。
     * 这条是实测踩出来的：本机库里有一行逻辑删除的楼栋，问"有几栋楼"真的多出来一栋。
     */
    @Test
    void injectsLogicDeleteConditionWhenTableHasThatColumn() {
        SqlSafetyResult result = gatewayWithLogicDelete.validateAndScope("SELECT id FROM ticket", TENANT);

        assertThat(result.sql())
                .isEqualTo("SELECT id FROM ticket WHERE ticket.tenant_id = 7 AND ticket.deleted = 0");
    }

    @Test
    void everyJoinedTableGetsLogicDeleteConditionToo() {
        SqlSafetyResult result = gatewayWithLogicDelete.validateAndScope(
                "SELECT b.name FROM ticket t JOIN building b ON t.building_id = b.id", TENANT);

        assertThat(result.sql()).contains("t.deleted = 0").contains("b.deleted = 0");
    }

    /** 没有 deleted 列的表**不能**注入——注了 SQL 直接报"未知列"，那是把好查询打死。 */
    @Test
    void doesNotInjectLogicDeleteForTableWithoutThatColumn() {
        SqlSafetyGateway onlyTicketHasDeleted = new SqlSafetyGateway(
                (table, column) -> "deleted".equals(column) && "ticket".equals(table));

        SqlSafetyResult result = onlyTicketHasDeleted.validateAndScope(
                "SELECT b.name FROM ticket t JOIN building b ON t.building_id = b.id", TENANT);

        assertThat(result.sql()).contains("t.deleted = 0").doesNotContain("b.deleted");
    }

    @Test
    void trailingSemicolonIsStripped() {
        SqlSafetyResult result = gateway.validateAndScope("SELECT id FROM ticket;", TENANT);

        assertThat(result.sql()).isEqualTo("SELECT id FROM ticket WHERE ticket.tenant_id = 7");
    }

    // ==================== 拒绝：白名单与语句形态 ====================

    @Test
    void rejectsBlankSql() {
        assertRejected(null, "模型没有生成 SQL");
        assertRejected("   ", "模型没有生成 SQL");
        assertRejected("```sql\n```", "模型没有生成 SQL");
    }

    @Test
    void rejectsTableOutsideWhitelist() {
        // tenant 表是平台层的，不属于任何租户；notification 是个人收件箱，都不是分析对象
        assertRejected("SELECT id FROM tenant", "表不在可查询范围内");
        assertRejected("SELECT id FROM notification", "表不在可查询范围内");
    }

    /**
     * 子查询一律拒绝——**这是评测集发现的真实漏洞**（docs/07 记了整条链路）。
     *
     * <p>租户条件只注入在外层 FROM/JOIN 的每一张表上，子查询里的表拿不到。所以
     * {@code SELECT (SELECT COUNT(*) FROM sys_user WHERE tenant_id <> 1) FROM building ...}
     * 里那个子查询**一个字都不会被改**：后勤管理员问一句就读到了别家学校的用户数（本机实测返回 2）。
     * {@code WHERE} 里的子查询同样能用 {@code > 0} 这种写法当布尔预言机逐位取数。
     *
     * <p>第二种写法是"还有多少工单没有评价"的标准答案，被这条规则挡下后模型会改用
     * {@code LEFT JOIN ... IS NULL}（提示词里写了），代价是偶尔要重问一次——
     * 比"安静地多返回别家数据"值得。
     */
    @Test
    void rejectsSubqueryAnywhere() {
        // 投影里的标量子查询：真正的数据泄露形态
        assertRejected("SELECT (SELECT COUNT(*) FROM sys_user WHERE tenant_id <> 1) AS x FROM building",
                "不支持子查询");
        // WHERE 里的 IN 子查询：过滤条件也不允许（布尔预言机）
        assertRejected("SELECT id FROM ticket WHERE building_id IN (SELECT id FROM building)",
                "不支持子查询");
        // EXISTS
        assertRejected("SELECT id FROM ticket WHERE EXISTS (SELECT 1 FROM building)", "不支持子查询");
        // 右表被当成数据源（本来就被 FROM 那条挡下，这里确认它在子查询规则下也过不去）
        assertRejected("SELECT t.id FROM ticket t JOIN (SELECT id FROM building) b ON t.building_id = b.id",
                "不支持子查询");
    }

    /**
     * 子查询里的表**连白名单都到不了**：子查询规则在更前面，先把它整条拒掉。
     * 白名单那道仍然在（挡住直接写 {@code FROM tenant}），两道闸门是叠加的。
     */
    @Test
    void rejectsTableHiddenInSubquery() {
        assertRejected("SELECT id FROM ticket WHERE building_id IN (SELECT id FROM tenant)", "不支持子查询");
    }

    @Test
    void rejectsSchemaQualifiedTableName() {
        // 带库名前缀不通过：白名单是"精确允许"，不做"看起来像同一张表"的推断
        assertRejected("SELECT id FROM repair.ticket", "表不在可查询范围内");
    }

    @Test
    void rejectsDelete() {
        assertRejected("DELETE FROM ticket WHERE id = 1", "只允许单条 SELECT");
    }

    @Test
    void rejectsUpdate() {
        assertRejected("UPDATE ticket SET status = 50", "只允许单条 SELECT");
    }

    @Test
    void rejectsDropTable() {
        assertRejected("DROP TABLE ticket", "只允许单条 SELECT");
    }

    @Test
    void rejectsMultiStatement() {
        // 关键：不能依赖"解析器会丢掉尾巴"。parseStatements 显式数一遍，多语句一律拒绝
        assertRejected("SELECT id FROM ticket; DROP TABLE ticket", "只允许单条 SELECT");
        assertRejected("SELECT id FROM ticket; SELECT id FROM building", "只允许单条 SELECT");
    }

    @Test
    void rejectsUnion() {
        // 每个分支都要各自抽取表、各自注入租户条件，漏一个就是一条越权路——所以直接不支持
        assertRejected("SELECT id FROM ticket UNION SELECT id FROM ticket_log", "只允许单条 SELECT");
    }

    @Test
    void rejectsWithClause() {
        // CTE 挂在 Select 上，所以它**也是 PlainSelect**，必须单独挡（否则 CTE 定义里的表不会被注入租户条件）
        assertRejected("WITH x AS (SELECT id FROM ticket) SELECT * FROM x", "不支持 WITH");
    }

    @Test
    void rejectsDerivedTableInFrom() {
        // 数据源藏在嵌套里就没法逐表注入租户条件。
        // 现在先被子查询规则挡下（更早、更宽），FROM 那道仍然在（见 outerTables），两道是叠加的
        assertRejected("SELECT * FROM (SELECT id FROM ticket) x", "不支持子查询");
    }

    @Test
    void rejectsQueryWithoutFrom() {
        // 没有 FROM 的查询对"问数据"没用，却能探测服务器信息、拖住连接
        assertRejected("SELECT @@version", "查询必须从白名单表里取数");
        assertRejected("SELECT SLEEP(10)", "查询必须从白名单表里取数");
    }

    @Test
    void rejectsUnparsableSql() {
        assertRejected("这不是 SQL", "无法解析");
    }

    // ==================== 拒绝：敏感列与写文件 ====================

    @Test
    void rejectsSensitiveColumn() {
        assertRejected("SELECT phone FROM sys_user", "不允许查询敏感字段");
        assertRejected("SELECT password FROM sys_user", "不允许查询敏感字段");
    }

    @Test
    void rejectsSensitiveColumnAsAlias() {
        // 换个名字不改变"读的是敏感列"这件事
        assertRejected("SELECT real_name AS phone FROM sys_user", "不允许查询敏感字段");
    }

    @Test
    void rejectsSensitiveWordEvenInLiteral() {
        // 这条记录的是**有意的误拒**：词法检查分不清标识符与字符串字面量。
        // 误拒的代价是模型换个说法重写；漏放的代价是手机号被读出来——所以选前者（理由见网关注释）
        assertRejected("SELECT id FROM sys_user WHERE real_name = 'phone'", "不允许查询敏感字段");
    }

    @Test
    void rejectsIntoOutfile() {
        // 这是写文件不是查询，只读账号挡不住它。
        // 实测：jsqlparser 5.2 解析不了 INTO OUTFILE，所以走的是"无法解析"这条路径；
        // 网关里另有一道 getIntoTables 检查留着（不依赖解析器的这个行为），只是当前不可达
        assertRejected("SELECT id FROM ticket INTO OUTFILE '/tmp/x'", "无法解析");
    }

    /**
     * 断言"注入的 SQL 长什么样"时把空白压平：jsqlparser 的 {@code toString()} 会自己决定
     * 换行与空格（例如 JOIN 前后换行），锁死空白的断言会在升级解析器时莫名其妙地红，
     * 而它想表达的其实是"条件在不在 ON 里"。
     */
    private String compact(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private void assertRejected(String sql, String messagePart) {
        assertThatThrownBy(() -> gateway.validateAndScope(sql, TENANT))
                .as("应被拒绝：%s", sql)
                .isInstanceOf(BizException.class)
                .hasMessageContaining(messagePart)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_SQL_REJECTED);
    }
}
