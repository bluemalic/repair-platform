package com.bluemalic.repair.interceptor;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
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
 * 数据权限处理器（ADR-002 的落地）：按当前登录角色，给 ticket 表的查询/更新/删除自动注入范围条件。
 *
 * <p>规则（依据 = docs/02 的 worker_building 表 + ticket.student_id）：
 * <ul>
 *   <li>后勤管理（ADMIN）→ 不限制</li>
 *   <li>维修工（WORKER）→ building_id IN (worker_building 里他负责的楼栋)</li>
 *   <li>学生（默认）→ student_id = 当前用户</li>
 * </ul>
 *
 * <p>依赖用 {@link ObjectProvider} 惰性获取：handler 被 MybatisPlusInterceptor 构造期引用，
 * 若直接注入 Mapper 会形成 sqlSessionFactory ↔ 拦截器的循环依赖，启动直接失败。
 *
 * <p>其余两点：
 * <ul>
 *   <li>loginId 为 null 时不注入——那是无登录态的"系统上下文"（定时任务、初始化）；
 *       HTTP 路径上的匿名访问已由 SaInterceptor 挡在 Controller 之前，到不了这里</li>
 *   <li>条件对单表查询验证过；带别名的多表 JOIN 需要把 Column 限定为别名列，引入 JOIN 时再补</li>
 * </ul>
 */
@Component
public class TicketDataScopeHandler implements MultiDataPermissionHandler {

    private final ObjectProvider<StpInterface> stpInterface;
    private final ObjectProvider<WorkerBuildingMapper> workerBuildingMapper;

    public TicketDataScopeHandler(ObjectProvider<StpInterface> stpInterface,
                                  ObjectProvider<WorkerBuildingMapper> workerBuildingMapper) {
        this.stpInterface = stpInterface;
        this.workerBuildingMapper = workerBuildingMapper;
    }

    @Override
    public Expression getSqlSegment(Table table, Expression where, String mappedStatementId) {
        String tableName = table.getName().replace("`", "").toLowerCase();

        // 通知：归属不随角色变化——任何人（含 ADMIN）都只能操作 receiver_id = 自己的。
        // 必须放在角色逻辑之前：ADMIN 在 ticket 上豁免（不影响 ticket 那套），在 notification 上不豁免。
        if ("notification".equals(tableName)) {
            Long userId = currentUserIdOrNull();
            return userId == null ? null : equalsColumn("receiver_id", userId);
        }

        if (!"ticket".equals(tableName)) {
            return null;
        }
        Long userId = currentUserIdOrNull();
        if (userId == null) {
            return null;
        }
        List<String> roles = stpInterface.getObject().getRoleList(userId, StpUtil.getLoginType());

        // 注意契约：只返回"要追加的范围条件"，拦截器自己会把它 AND 到原 WHERE 上——
        // 不要把传入的 where 拼进返回值，否则条件会重复两遍
        if (roles.contains("ADMIN")) {
            return null;
        }
        if (roles.contains("WORKER")) {
            List<Long> buildingIds = workerBuildingMapper.getObject().selectList(
                            Wrappers.<WorkerBuilding>lambdaQuery().eq(WorkerBuilding::getWorkerId, userId))
                    .stream().map(WorkerBuilding::getBuildingId).distinct().toList();
            if (buildingIds.isEmpty()) {
                // 不负责任何楼栋的维修工：一条也看不到（1=0 恒假条件）
                return parse("1 = 0");
            }
            InExpression in = new InExpression();
            in.setLeftExpression(new Column("building_id"));
            // jsqlparser 5.x：IN 的右侧必须用带括号的列表，裸 ExpressionList 会渲染成 "IN 1"
            in.setRightExpression(new ParenthesedExpressionList<>(
                    buildingIds.stream().map(LongValue::new).toList()));
            return in;
        }
        // 学生（以及任何未配置特殊范围的角色）
        return equalsColumn("student_id", userId);
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

    private Expression equalsColumn(String column, long value) {
        EqualsTo eq = new EqualsTo();
        eq.setLeftExpression(new Column(column));
        eq.setRightExpression(new LongValue(value));
        return eq;
    }

    private Expression parse(String sql) {
        try {
            return CCJSqlParserUtil.parseCondExpression(sql);
        } catch (JSQLParserException e) {
            throw new IllegalStateException("数据权限条件解析失败: " + sql, e);
        }
    }
}
