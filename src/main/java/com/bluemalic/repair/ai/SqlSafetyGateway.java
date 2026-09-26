package com.bluemalic.repair.ai;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 安全网关：ADR-003 四层闸门里的**第 2 层（白名单）与第 3 层（租户条件）**。
 *
 * <p>第 1 层（只读账号）在 {@code AiReadOnlyAccountBootstrap} + {@code AiQueryExecutor}，
 * 第 4 层（超时与行数上限）在 {@code AiQueryExecutor}——它们都需要真实连接，所以不在这里。
 * 这个类**不碰模型、不碰数据库**：输入一段 SQL 与一个租户 ID，输出可直接执行的 SQL，
 * 或者抛 {@code 40002} 拒绝。因此它可以被完整地单测（见 {@code SqlSafetyGatewayTest}）。
 *
 * <p><b>为什么安全要写在代码里而不是提示词里</b>（ADR-003）：提示词约束不了模型行为——
 * 同一个 prompt 换一个问题就可能失效，而且不同模型表现不一致、无法验证。这里的立场是
 * **不信任模型输出**：不管它生成什么，最后都要过这道闸门。
 *
 * <p><b>第 3 层在这条路上的形态是"注入"而不是"拒绝"，也不走 MyBatis 拦截器</b>：
 * 数据权限拦截器只覆盖 {@code ticket} / {@code notification} 两张表（AGENTS §5.6），而 AI 的 SQL
 * 可能查白名单里的任何一张表——只靠拦截器，{@code SELECT * FROM building} 就会读到别家租户的楼栋。
 * 所以这里自己在 AST 上给每张表注入 {@code tenant_id = 当前租户}，复用
 * {@code TicketDataScopeHandler} 的手法（别名优先限定、{@code AndExpression} 组合）。
 *
 * <p>注入有个**重要性质**：条件是 **AND** 上去的。模型就算写了 {@code tenant_id = 999}，
 * 最终也是 {@code tenant_id = 999 AND tenant_id = 本租户} → 空结果。也就是说它**只能收窄、
 * 不能放宽**——这正是"注入"比"校验模型有没有写租户条件"更可靠的地方。
 *
 * <p>角色维度对 M4 不适用：{@code ai:query} 只授后勤管理，而后勤在本租户内不受角色限制。
 * 将来若把问数开放给学生或维修工，这里必须补上角色维度（那时也不能只靠拦截器，理由同上）。
 */
@Slf4j
@Component
public class SqlSafetyGateway {

    /** 每张白名单表都有的列（{@link SchemaWhitelist} 的类注释里写明了这条约束）。 */
    private static final String TENANT_COLUMN = "tenant_id";

    /** 逻辑删除列：本项目的约定是 {@code deleted = 1} 表示"已删除"。 */
    private static final String DELETED_COLUMN = "deleted";

    /** 从 SQL 文本里切出标识符，用于敏感列检查（理由见 {@link #firstSensitiveToken}）。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** 数 {@code SELECT} 关键字用（理由见 {@link #rejectSubqueries}）。 */
    private static final Pattern SELECT_KEYWORD = Pattern.compile("\\bselect\\b", Pattern.CASE_INSENSITIVE);

    private final ColumnLookup columnLookup;

    public SqlSafetyGateway(ColumnLookup columnLookup) {
        this.columnLookup = columnLookup;
    }

    /**
     * 校验 + 注入租户条件。通过则返回可直接执行的 SQL；不通过抛 {@code 40002}（带具体原因）。
     *
     * @param rawSql   模型生成的原始 SQL（可能带 markdown 代码围栏）
     * @param tenantId 当前登录者的租户；调用方负责保证它来自登录态而不是请求参数
     */
    public SqlSafetyResult validateAndScope(String rawSql, long tenantId) {
        String sql = normalize(rawSql);

        Statement statement = parseSingleStatement(sql);
        if (!(statement instanceof PlainSelect select)) {
            // 这一条同时挡掉 UNION（SetOperationList）与任何非 SELECT 语句。
            // 为什么连 UNION 也挡：每个分支都要各自抽取表、各自注入租户条件，漏一个分支就是一条越权路；
            // 而"对比两周/两个楼栋"这类问题用 GROUP BY 或 CASE WHEN 都能表达（提示词里也写了）。
            throw reject("只允许单条 SELECT 查询（不支持 UNION / WITH / 非查询语句）", sql);
        }
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            // WITH 的 CTE 在 AST 上挂在 Select 上，所以它**也是 PlainSelect**——必须在类型判断之后单独挡掉。
            // 不挡的话，CTE 定义里的表不会被注入租户条件（外层注入的是 CTE 的名字，不是它背后的表）
            throw reject("不支持 WITH（公共表表达式），请直接用 JOIN 或子查询", sql);
        }
        rejectFileWrite(select, sql);
        rejectSubqueries(sql);

        // 表白名单：TablesNamesFinder 会连子查询里的表一起找出来，所以 WHERE 里的子查询同样受约束
        // （用接收已解析语句的实例方法：不用再解析一遍，也不抛受检异常）
        Set<String> tables = new TablesNamesFinder().getTables(statement);
        for (String name : tables) {
            if (!SchemaWhitelist.isAllowed(name)) {
                throw reject("表不在可查询范围内：" + name, sql);
            }
        }

        String sensitive = firstSensitiveToken(sql);
        if (sensitive != null) {
            throw reject("不允许查询敏感字段：" + sensitive, sql);
        }

        List<Table> sources = outerTables(select, sql);
        injectScope(select, sources, tenantId);

        String scoped = select.toString();
        log.info("AI 生成的 SQL 通过安全网关 tenantId={} tables={} sql={}", tenantId, tables, scoped);
        return new SqlSafetyResult(scoped, tables);
    }

    /**
     * 剥掉模型爱加的包装，但**只剥包装、不"顺手修"SQL**。
     *
     * <p>这里刻意不做"自动补全列名""把双引号换成单引号"之类的宽容处理：网关看不懂的 SQL
     * 就该被拒绝，而不是被猜出一个意图再执行。
     */
    private String normalize(String rawSql) {
        String sql = rawSql == null ? "" : rawSql.trim();
        if (sql.startsWith("```")) {
            int firstLineEnd = sql.indexOf('\n');
            if (firstLineEnd > 0) {
                sql = sql.substring(firstLineEnd + 1).trim();
            }
            if (sql.endsWith("```")) {
                sql = sql.substring(0, sql.length() - 3).trim();
            }
        }
        // 结尾分号是模型的口头习惯；只去结尾的，中间的分号留着——多语句要靠解析器拦，不能靠这里"清洗"
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.isEmpty()) {
            throw reject("模型没有生成 SQL", rawSql);
        }
        return sql;
    }

    /**
     * 解析，并要求**恰好一条语句**。
     *
     * <p>为什么不用 {@code parse(sql)}：它只解析第一条，剩下的被静默丢掉——
     * {@code SELECT id FROM ticket; DROP TABLE ticket} 会"通过"。虽然我最后返回的是
     * {@code select.toString()}（被丢掉的部分本来也执行不到），但把"多语句"这件事实依赖在
     * "解析器会丢掉尾巴"上太脆了：换个解析器版本、或者哪天有人改成返回原始 SQL，它就变成真漏洞。
     * 所以用 {@code parseStatements} 显式数一遍。
     */
    private Statement parseSingleStatement(String sql) {
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException e) {
            // 解析器的报错很长且带位置，不适合直接给用户看；完整信息进日志
            log.warn("AI 生成的 SQL 无法解析 sql={} err={}", sql, e.getMessage());
            throw reject("生成的 SQL 无法解析", sql);
        }
        if (statements.size() != 1) {
            throw reject("只允许单条 SELECT 查询（检测到 " + statements.size() + " 条语句）", sql);
        }
        return statements.get(0);
    }

    /**
     * {@code SELECT ... INTO OUTFILE} 是**写文件**，不是查询——只读账号挡不住它，这里显式拒绝。
     *
     * <p>说明：jsqlparser 5.2 解析不了这个语法，所以现实里它会在 {@link #parseSingleStatement}
     * 就被拒（测试断言的就是那条路径）。这一道检查留着是**不依赖解析器的这个行为**：
     * 万一将来升级解析器后它变成可解析，这里仍然拦得住。安全代码宁可多一道，不可少一道。
     */
    private void rejectFileWrite(PlainSelect select, String sql) {
        if (select.getIntoTables() != null && !select.getIntoTables().isEmpty()) {
            throw reject("不支持 INTO OUTFILE / INTO DUMPFILE", sql);
        }
    }

    /**
     * 拒绝**任何位置**的子查询（WHERE 里的 {@code IN (SELECT ...)}、投影里的标量子查询、
     * {@code EXISTS (...)}、以及被当作表用的 {@code FROM (SELECT ...)}）。
     *
     * <p><b>为什么一刀切</b>：租户条件是**按外层 FROM/JOIN 的每一张表**注入的（{@link #injectScope}），
     * 子查询里的表拿不到这个条件。于是下面这种形状会把别家租户的数据直接算进结果：
     * <pre>SELECT (SELECT COUNT(*) FROM sys_user WHERE tenant_id &lt;&gt; 1) AS x FROM building WHERE ...</pre>
     * 外层的 {@code building} 被注入成了本租户，但括号里那个子查询**一个字都没改**——
     * 后勤管理员问一句就能读到别家学校的用户数（本机实测：这条返回 2）。
     * WHERE 里的子查询同样是通道：{@code WHERE (SELECT COUNT(*) FROM ticket WHERE tenant_id = 2) > 0}
     * 是一个逐位提取信息的布尔预言机。
     *
     * <p>另一条路（递归进每个子查询再注入）被否掉，理由与"不许 UNION、不许 CTE、不许派生表"一致：
     * 每多一种要递归处理的形状，就多一处漏注入的可能，而漏注入是**静默**的——错了不会报错，
     * 只会安静地多返回数据。宁可用一刀切的方式 fail-closed：要表达"不在某集合里"用
     * {@code LEFT JOIN ... WHERE 右表.id IS NULL}，要比较用 JOIN + GROUP BY（提示词里写了）。
     *
     * <p>实现上用**词法**数 {@code SELECT} 关键字，不走 AST 遍历：AST 遍历同样可能漏掉某种嵌套形状
     * （漏掉就是放行），而"多一个 SELECT 关键字"是任何子查询都绕不过去的硬事实。代价是
     * 字符串字面量里出现 {@code select} 会被误拒（如 {@code LIKE '%select%'}）——这与敏感列检查
     * 是同一个取舍：**宁可偶尔误拒（模型换个说法即可），也不漏放**。
     */
    private void rejectSubqueries(String sql) {
        Matcher matcher = SELECT_KEYWORD.matcher(sql);
        int count = 0;
        while (matcher.find()) {
            count++;
            if (count > 1) {
                throw reject("不支持子查询（含 WHERE 里的 IN (SELECT ...)）："
                        + "要表达「不在某个集合里」请改用 LEFT JOIN 后判断右表主键为空", sql);
            }
        }
    }

    /**
     * 外层数据源：必须是**白名单表本身**，不能是子查询，且至少一张。
     *
     * <p>两条限制各有理由：
     * <ul>
     *   <li><b>不能是子查询</b>（{@code FROM (SELECT ...) x}）：那等于把真正的数据源藏进嵌套里，
     *       而"注入租户条件"是按外层 FROM/JOIN 逐表做的。要支持它就得递归改写每个嵌套查询——
     *       多一处递归就多一处漏注入的可能，而模型完全可以用 JOIN 表达同样的意思
     *       （子查询整体也已被 {@link #rejectSubqueries} 拦下，这里是它之后更细的一道）</li>
     *   <li><b>至少一张表</b>：没有 FROM 的 {@code SELECT @@version} / {@code SELECT SLEEP(10)}
     *       既能探测服务器信息又能拖住连接，而它们对"问数据"毫无用处</li>
     * </ul>
     */
    private List<Table> outerTables(PlainSelect select, String sql) {
        FromItem from = select.getFromItem();
        if (from == null) {
            throw reject("查询必须从白名单表里取数（不支持没有 FROM 的查询）", sql);
        }
        if (!(from instanceof Table first)) {
            throw reject("数据源只能是白名单里的表，不能用子查询", sql);
        }
        List<Table> tables = new ArrayList<>();
        tables.add(first);
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                if (!(join.getRightItem() instanceof Table joined)) {
                    throw reject("JOIN 的对象只能是白名单里的表", sql);
                }
                if (join.isRight() || join.isFull()) {
                    // RIGHT / FULL JOIN 的"可空一侧"是**左边**，而左边那些表的条件只能进 WHERE
                    // ——那会多滤掉一批行（与 LEFT JOIN 在 WHERE 上踩的是同一个坑，方向相反）。
                    // 模型没有理由写它（把两张表换个位置用 LEFT JOIN 就能表达），所以直接拒绝，
                    // 不去做半懂的改写：**宁可拒，不可错**。
                    throw reject("不支持 RIGHT / FULL JOIN，请改用 LEFT JOIN", sql);
                }
                tables.add(joined);
            }
        }
        return tables;
    }

    /**
     * 给每张表注入 {@code tenant_id = ?}（以及有逻辑删除列时的 {@code deleted = 0}）。
     *
     * <p><b>落点分两处：FROM 那张表进 WHERE，JOIN 进来的表进它自己的 ON。</b>
     * 这不是风格问题——{@code LEFT JOIN} 的右表条件放进 WHERE，会把"没匹配上"的行一起滤掉，
     * LEFT JOIN 悄悄退化成 INNER JOIN。评测集（docs/07）里就踩到了：模型写
     * {@code LEFT JOIN ticket_evaluation ON ... WHERE ticket_evaluation.id IS NULL}
     * （"还有多少工单没有评价"的标准反连接写法），注入的
     * {@code ticket_evaluation.tenant_id = 1} 在 WHERE 里把空匹配那些行全滤掉，
     * **答案从 6 变成 0**（本机实测）。注到 ON 上就对了：右表仍然只匹配本租户的行，
     * 但"没匹配上"的行被 LEFT JOIN 保留下来。
     *
     * <p>INNER JOIN 两处等价，CROSS JOIN / 逗号连接没有"未匹配也保留"的一侧（两处也等价），
     * 所以只有带 ON 的 JOIN 要挪进 ON。**收窄性质不变**：条件仍是 AND 上去的，
     * 原来 ON 里的表达式会被加括号（{@code ON a OR b} 拼成 {@code ON (a OR b) AND tenant = 1}），
     * 模型写不出"放宽"。
     *
     * <p><b>为什么要注入 {@code deleted = 0}</b>：项目里所有查询都走 MyBatis-Plus，而它的逻辑删除
     * 会自动补上这个条件——AI 的 SQL 走的是裸 JDBC，**绕过了那层**。不补的话它会把已删除的楼栋、
     * 已删除的工单一起算进去，于是"AI 说 6 栋楼、界面显示 5 栋"这种矛盾立刻出现（而口径不一致
     * 是最伤信任的一类问题）。是否注入取决于那张表有没有这一列，所以要看 {@link ColumnLookup}。
     *
     * <p><b>原有 WHERE 与 ON 都要加括号</b>：否则 {@code a OR b} 拼上 AND 会渲染成
     * {@code a OR b AND tenant}，按优先级读成 {@code a OR (b AND tenant)}——语义被悄悄改掉，
     * 而且改的方向是"多返回数据"。这是拼接条件时最容易踩的一个坑，测试里有专门一条盯着它。
     */
    private void injectScope(PlainSelect select, List<Table> tables, long tenantId) {
        // FROM 那张表的条件：没有"外层连接"会把它变成可空的一侧（除非是 RIGHT JOIN，见下）
        Expression whereScope = scopeOf(tables.get(0), tenantId);

        List<Join> joins = select.getJoins();
        for (int i = 0; joins != null && i < joins.size(); i++) {
            Join join = joins.get(i);
            Expression scope = scopeOf(tables.get(i + 1), tenantId);
            if (join.isCross() || join.getOnExpressions().isEmpty()) {
                // 没有 ON 的连接（CROSS JOIN / 不带条件的 JOIN）：不存在"未匹配也保留"的一侧，
                // WHERE 与 ON 等价
                whereScope = and(whereScope, scope);
            } else {
                // 多个 ON 子句（jsqlparser 支持、MySQL 少见）合成一个再把条件 AND 上去，语义不变
                Expression on = null;
                for (Expression expression : join.getOnExpressions()) {
                    on = and(on, expression);
                }
                join.setOnExpressions(List.of(and(parenthesized(on), scope)));
            }
        }

        Expression existing = select.getWhere();
        select.setWhere(existing == null ? whereScope
                : new AndExpression(parenthesized(existing), whereScope));
    }

    /** 单张表的范围条件：租户一定有，逻辑删除列按表结构决定。 */
    private Expression scopeOf(Table table, long tenantId) {
        Expression scope = columnEquals(table, TENANT_COLUMN, tenantId);
        if (columnLookup.has(table.getName().replace("`", "").toLowerCase(), DELETED_COLUMN)) {
            scope = and(scope, columnEquals(table, DELETED_COLUMN, 0L));
        }
        return scope;
    }

    /**
     * 加一层括号。jsqlparser 5.x 的 {@code Parenthesis} 没有"接收表达式"的构造器
     * （{@code withExpression} 是"替换第 0 个元素"，空列表上会 IndexOutOfBounds），
     * 所以先建空括号再把条件放进去。
     */
    private Expression parenthesized(Expression expression) {
        Parenthesis parenthesis = new Parenthesis();
        parenthesis.add(expression);
        return parenthesis;
    }

    private Expression and(Expression left, Expression right) {
        return left == null ? right : new AndExpression(left, right);
    }

    private Expression columnEquals(Table table, String column, long value) {
        EqualsTo condition = new EqualsTo();
        condition.setLeftExpression(new Column(qualified(table, column)));
        condition.setRightExpression(new LongValue(value));
        return condition;
    }

    /**
     * 敏感列检查走**词法**（切标识符）而不是 AST 遍历。
     *
     * <p>取舍：AST 遍历要再写一套 visitor（本项目已经刻意避开它，见 {@link #outerTables}）；
     * 而 {@code phone} / {@code password} 出现在标识符位置的概率极高、出现在字符串字面量里的概率极低。
     * 所以这个检查**宁可偶尔误拒**（比如 {@code WHERE real_name = 'phone'} 会被拦），也不要漏放——
     * 误拒的代价是模型换个说法重写，漏放的代价是手机号被读出来。
     */
    private String firstSensitiveToken(String sql) {
        Matcher matcher = IDENTIFIER.matcher(sql);
        while (matcher.find()) {
            String token = matcher.group();
            if (SchemaWhitelist.isSensitiveColumn(token)) {
                return token;
            }
        }
        return null;
    }

    /** 条件列限定到表名或别名：JOIN 场景别名优先，避免与其他表同名列歧义。 */
    private String qualified(Table table, String column) {
        String prefix = table.getAlias() != null ? table.getAlias().getName() : table.getName();
        return prefix + "." + column;
    }

    /** 复用 40002（生成的 SQL 未通过安全校验），文案说清是哪一条没过——不新造错误码。 */
    private BizException reject(String reason, String sql) {
        log.warn("AI 生成的 SQL 被安全网关拒绝 reason={} sql={}", reason, sql);
        return new BizException(ErrorCode.AI_SQL_REJECTED, reason);
    }
}
