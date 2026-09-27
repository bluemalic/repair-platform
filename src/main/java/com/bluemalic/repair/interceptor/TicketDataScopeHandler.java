package com.bluemalic.repair.interceptor;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.bluemalic.repair.service.CurrentTenantService;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据权限处理器（ADR-002 / ADR-008 的落地）：给 ticket 表的查询/更新自动注入**两层**范围条件。
 *
 * <p><b>第一层：租户</b>（ADR-008）——所有角色都受本租户约束，后勤也不再跨租户全可见：
 * <ul>
 *   <li>后勤管理（ADMIN）→ 本租户内不限制</li>
 *   <li>维修工（WORKER）→ 本租户内 {@code building_id IN (他负责的楼栋) OR worker_id = 他}——
 *       后一半是跨楼栋强制派单的单（见 {@link #buildScopeExpression} 的说明）</li>
 *   <li>学生（默认）→ 本租户内 student_id = 当前用户</li>
 * </ul>
 *
 * <p>条件列一律限定到表名/别名（{@link #qualified}）：单表查询时是 {@code ticket.student_id}，
 * 带别名的多表 JOIN（统计看板等）时为 {@code t.student_id}，避免列歧义。
 *
 * <p>依赖用 {@link ObjectProvider} 惰性获取：handler 被 MybatisPlusInterceptor 构造期引用，
 * 若直接注入 Mapper 会形成 sqlSessionFactory ↔ 拦截器的循环依赖，启动直接失败。
 *
 * <p>其余两点：
 * <ul>
 *   <li><b>loginId 为 null 时完全不注入</b>——那是无登录态的"系统上下文"（定时任务、初始化）。
 *       超时兜底扫描正是靠这一点跨租户处理；HTTP 路径上的匿名访问已由 SaInterceptor 挡在 Controller 之前</li>
 *   <li>条件构建抽成 {@link #buildScopedExpression} 纯函数（租户+角色），方便单测；
 *       本类只在 {@link #getSqlSegment} 里负责"取当前登录态"这一件有副作用的事</li>
 * </ul>
 */
@Component
public class TicketDataScopeHandler implements MultiDataPermissionHandler {

    private final ObjectProvider<StpInterface> stpInterface;
    private final ObjectProvider<WorkerBuildingMapper> workerBuildingMapper;
    private final ObjectProvider<CurrentTenantService> currentTenantService;

    public TicketDataScopeHandler(ObjectProvider<StpInterface> stpInterface,
                                  ObjectProvider<WorkerBuildingMapper> workerBuildingMapper,
                                  ObjectProvider<CurrentTenantService> currentTenantService) {
        this.stpInterface = stpInterface;
        this.workerBuildingMapper = workerBuildingMapper;
        this.currentTenantService = currentTenantService;
    }

    @Override
    public Expression getSqlSegment(Table table, Expression where, String mappedStatementId) {
        String tableName = table.getName().replace("`", "").toLowerCase();

        Long userId = currentUserIdOrNull();
        if (userId == null) {
            // 无登录态 = 系统上下文（定时任务、初始化）→ 不注入任何条件。
            // 超时兜底扫描正是靠这一点跨租户处理，别在这里加"默认限制当前租户"。
            return null;
        }
        Long tenantId = tenantIdOrNull();

        // 通知：归属不随角色变化——任何人（含 ADMIN）都只能操作 receiver_id = 自己的。
        // 必须放在角色逻辑之前：ADMIN 在 ticket 上豁免角色限制，在 notification 上不豁免。
        if ("notification".equals(tableName)) {
            Expression mine = equalsColumn(table, "receiver_id", userId);
            return tenantId == null ? mine
                    : new AndExpression(tenantCondition(table, tenantId), mine);
        }

        if (!"ticket".equals(tableName)) {
            return null;
        }
        List<String> roles = stpInterface.getObject().getRoleList(userId, StpUtil.getLoginType());
        List<Long> buildingIds = roles.contains("WORKER")
                ? workerBuildingMapper.getObject().selectList(
                        Wrappers.<WorkerBuilding>lambdaQuery().eq(WorkerBuilding::getWorkerId, userId))
                        .stream().map(WorkerBuilding::getBuildingId).distinct().toList()
                : List.of();
        return buildScopedExpression(table, userId, tenantId, roles, buildingIds);
    }

    /**
     * 租户 + 角色两层条件（纯函数，便于单测）。**顺序即语义：先按租户隔离，再按角色隔离**——
     * 租户是第一层，所以后勤管理也不再"跨租户全可见"，只是"本租户内不受限"。
     *
     * <p>契约同 MP：只返回要追加的条件，拦截器自己 AND 到原 WHERE 上。
     */
    Expression buildScopedExpression(Table table, long userId, Long tenantId,
                                     List<String> roles, List<Long> buildingIds) {
        Expression roleScope = buildScopeExpression(table, userId, roles, buildingIds);
        if (tenantId == null) {
            // 理论上不会走到（有登录态就有租户）；留个保守分支：宁可只按角色限制，也不放开全部
            return roleScope;
        }
        return roleScope == null ? tenantCondition(table, tenantId)
                : new AndExpression(tenantCondition(table, tenantId), roleScope);
    }

    /**
     * 角色维度的条件（不含租户）。ADMIN 返回 null —— 表示"角色维度不限制"，
     * 是否还有租户条件由 {@link #buildScopedExpression} 决定。
     *
     * <p><b>维修工这一条是"或"，不是"只按楼栋"</b>：{@code building_id IN (负责楼栋) OR worker_id = 我}。
     * 后一半是给**跨楼栋强制派单**（后勤临时抽调，见 `docs/01` §4.2）留的：可见范围必须与操作权一致，
     * 否则"能派给他、他列表里却看不到"，这张单就成了没人能操作的孤儿。
     * OR 必须加括号（AND 优先级更高），否则拼上租户条件会变成 {@code 租户 AND 楼栋 OR 派给我}——
     * 后一个分支把租户条件绕过去了。测试里有一条专门盯这个括号。
     */
    Expression buildScopeExpression(Table table, long userId, List<String> roles, List<Long> buildingIds) {
        if (roles.contains("ADMIN")) {
            return null;
        }
        if (roles.contains("WORKER")) {
            // 一个楼栋都不负责的师傅：左边恒假，整体等价于"只看派给我的单"（不再特判成 1 = 0）
            Expression byBuilding = buildingIds.isEmpty() ? parse("1 = 0") : inBuildings(table, buildingIds);
            return parenthesis(new OrExpression(byBuilding, equalsColumn(table, "worker_id", userId)));
        }
        // 学生（以及任何未配置特殊范围的角色）
        return equalsColumn(table, "student_id", userId);
    }

    private Expression inBuildings(Table table, List<Long> buildingIds) {
        InExpression in = new InExpression();
        in.setLeftExpression(new Column(qualified(table, "building_id")));
        // jsqlparser 5.x：IN 的右侧必须用带括号的列表，裸 ExpressionList 会渲染成 "IN 1"
        in.setRightExpression(new ParenthesedExpressionList<>(
                buildingIds.stream().map(LongValue::new).toList()));
        return in;
    }

    /**
     * 包一层括号。jsqlparser 5.x 的 {@code Parenthesis} 没有"接收表达式"的构造器
     * （{@code withExpression} 是"替换第 0 个元素"，空列表上会 IndexOutOfBounds），所以先建空括号再 add。
     */
    private Expression parenthesis(Expression expression) {
        Parenthesis parenthesis = new Parenthesis();
        parenthesis.add(expression);
        return parenthesis;
    }

    private Expression tenantCondition(Table table, long tenantId) {
        return equalsColumn(table, "tenant_id", tenantId);
    }

    /** 当前登录者的租户；无登录态返回 null（与 {@link #currentUserIdOrNull} 同一套判断）。 */
    private Long tenantIdOrNull() {
        try {
            return currentTenantService.getObject().tenantIdOrNull();
        } catch (Exception e) {
            return null;
        }
    }

    /** 拿当前登录用户 ID；无登录态（含定时任务的"系统上下文"）返回 null。 */
    private Long currentUserIdOrNull() {
        Object loginId;
        try {
            loginId = StpUtil.getLoginIdDefaultNull();
        } catch (Exception e) {
            // SaTokenContext 尚未初始化（纯 Mapper 调用 / 定时任务的"系统上下文"）→ 视为无登录态，不注入
            return null;
        }
        return loginId == null ? null : Long.parseLong(String.valueOf(loginId));
    }

    private Expression equalsColumn(Table table, String column, long value) {
        EqualsTo eq = new EqualsTo();
        eq.setLeftExpression(new Column(qualified(table, column)));
        eq.setRightExpression(new LongValue(value));
        return eq;
    }

    /** 条件列限定到表名或别名：JOIN 场景别名优先，避免与其他表同名列歧义。 */
    private String qualified(Table table, String column) {
        String prefix = table.getAlias() != null ? table.getAlias().getName() : table.getName();
        return prefix + "." + column;
    }

    private Expression parse(String sql) {
        try {
            return CCJSqlParserUtil.parseCondExpression(sql);
        } catch (JSQLParserException e) {
            throw new IllegalStateException("数据权限条件解析失败: " + sql, e);
        }
    }
}
