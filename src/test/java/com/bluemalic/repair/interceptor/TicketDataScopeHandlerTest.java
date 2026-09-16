package com.bluemalic.repair.interceptor;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.schema.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据权限条件构建的单元测试：不依赖 Spring / 登录态，专测 {@code buildScopedExpression} 输出的 SQL。
 *
 * <p>两条核心断言：
 * <ul>
 *   <li><b>租户是第一层</b>（ADR-008）：后勤管理不再"跨租户全可见"，它只在本租户内不受限；
 *       所有角色的条件都以 {@code tenant_id = ?} 开头</li>
 *   <li><b>条件列限定到表名/别名</b>：单表时是 {@code ticket.student_id}，带别名 JOIN 时是
 *       {@code t.student_id}——否则统计看板的多表查询会列歧义</li>
 * </ul>
 */
class TicketDataScopeHandlerTest {

    private static final long TENANT = 1L;

    private final TicketDataScopeHandler handler = new TicketDataScopeHandler(null, null, null);

    private Table ticketTable() {
        return new Table("ticket");
    }

    private Table ticketAliased(String alias) {
        Table table = new Table("ticket");
        table.setAlias(new Alias(alias));
        return table;
    }

    @Test
    void tenantIsTheFirstLayerForEveryRole() {
        // 后勤：角色维度不限制，但租户维度仍然生效
        assertThat(handler.buildScopedExpression(ticketTable(), 1L, TENANT, List.of("ADMIN"), List.of()).toString())
                .isEqualTo("ticket.tenant_id = 1");

        // 学生：租户 + 本人
        assertThat(handler.buildScopedExpression(ticketTable(), 3L, TENANT, List.of("STUDENT"), List.of()).toString())
                .isEqualTo("ticket.tenant_id = 1 AND ticket.student_id = 3");

        // 维修工：租户 + 负责楼栋；带别名 JOIN 时列限定到别名
        assertThat(handler.buildScopedExpression(ticketAliased("t"), 2L, TENANT, List.of("WORKER"), List.of(1L, 3L))
                .toString())
                .isEqualTo("t.tenant_id = 1 AND t.building_id IN (1, 3)");
    }

    @Test
    void workerWithoutBuildingsSeesNothingTenantStillApplied() {
        assertThat(handler.buildScopedExpression(ticketTable(), 2L, TENANT, List.of("WORKER"), List.of()).toString())
                .isEqualTo("ticket.tenant_id = 1 AND 1 = 0");
    }

    @Test
    void withoutTenantOnlyRoleScopeIsApplied() {
        // 有登录态却拿不到租户（理论兜底分支）：宁可只按角色限制，也不放开全部
        assertThat(handler.buildScopedExpression(ticketTable(), 3L, null, List.of("STUDENT"), List.of()).toString())
                .isEqualTo("ticket.student_id = 3");
        assertThat(handler.buildScopedExpression(ticketTable(), 1L, null, List.of("ADMIN"), List.of())).isNull();
    }
}
