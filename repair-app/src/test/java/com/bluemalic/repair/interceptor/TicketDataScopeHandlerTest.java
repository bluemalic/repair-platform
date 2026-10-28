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

        // 维修工：租户 + （负责楼栋 **或** 派给我的单 **或** 我协作的单）；带别名 JOIN 时列限定到别名。
        // 注意 jsqlparser 会把同优先级的嵌套 OR **拍平**（`(a OR b) OR c` 渲染成 `a OR b OR c`）——
        // 语义一模一样，而且渲染出来更好读；要紧的是外边那层括号，它在（见下一个用例）
        assertThat(handler.buildScopedExpression(ticketAliased("t"), 2L, TENANT, List.of("WORKER"), List.of(1L, 3L))
                .toString())
                .isEqualTo("t.tenant_id = 1 AND (t.building_id IN (1, 3) OR t.worker_id = 2"
                        + " OR t.id IN (SELECT ticket_id FROM ticket_collaborator WHERE worker_id = 2))");
    }

    /**
     * **这个括号是有分量的**：不包住 OR 的话，拼上租户条件会渲染成
     * {@code 租户 AND 楼栋 OR 派给我}——按优先级读成 {@code (租户 AND 楼栋) OR 派给我}，
     * 后面那些分支把租户条件整个绕过去了。所以断言里连括号一起钉住。
     */
    @Test
    void workerScopeIsParenthesizedSoTenantCannotBeBypassed() {
        String sql = handler.buildScopedExpression(ticketTable(), 2L, TENANT, List.of("WORKER"), List.of(9L))
                .toString();
        assertThat(sql).isEqualTo("ticket.tenant_id = 1 AND (ticket.building_id IN (9)"
                + " OR ticket.worker_id = 2"
                + " OR ticket.id IN (SELECT ticket_id FROM ticket_collaborator WHERE worker_id = 2))");
        assertThat(sql).doesNotContain("AND ticket.building_id IN (9) OR");
    }

    @Test
    void workerWithoutBuildingsSeesTicketsAssignedToHimAndOnesHeCollaboratesOn() {
        // 一个楼栋都不负责的师傅：左边恒假，仍然能看到"派给我的单"（跨楼栋强制派单的那类）与"我协作的单"
        assertThat(handler.buildScopedExpression(ticketTable(), 2L, TENANT, List.of("WORKER"), List.of()).toString())
                .isEqualTo("ticket.tenant_id = 1 AND (1 = 0 OR ticket.worker_id = 2"
                        + " OR ticket.id IN (SELECT ticket_id FROM ticket_collaborator WHERE worker_id = 2))");
    }

    /**
     * 协同处理的第三层（`docs/01` §4.2 / §4.5）：**被拉进这单的人必须看得到**。
     *
     * <p>少了这一条，现象与当初"跨楼栋派单"的坑一模一样——后勤能把人加进来，那个人的列表里却没有这张单，
     * 于是"两个人一起干"变成"只有主责一个人干"，而且全程没有任何报错。
     *
     * <p>断言盯住两件事：条件里确实有协作子查询，且**它在外层括号里面**（不能绕开租户条件）。
     * 子查询自己不用带 tenant_id——worker_id 是全局唯一的雪花 ID（理由见 handler 的注释）。
     */
    @Test
    void workerScopeIncludesTicketsHeCollaboratesOn() {
        String sql = handler.buildScopedExpression(ticketTable(), 7L, TENANT, List.of("WORKER"), List.of(9L))
                .toString();
        assertThat(sql)
                .contains("ticket.id IN (SELECT ticket_id FROM ticket_collaborator WHERE worker_id = 7)")
                .startsWith("ticket.tenant_id = 1 AND (")
                .endsWith("OR ticket.id IN (SELECT ticket_id FROM ticket_collaborator WHERE worker_id = 7))");
    }

    @Test
    void withoutTenantOnlyRoleScopeIsApplied() {
        // 有登录态却拿不到租户（理论兜底分支）：宁可只按角色限制，也不放开全部
        assertThat(handler.buildScopedExpression(ticketTable(), 3L, null, List.of("STUDENT"), List.of()).toString())
                .isEqualTo("ticket.student_id = 3");
        assertThat(handler.buildScopedExpression(ticketTable(), 1L, null, List.of("ADMIN"), List.of())).isNull();
    }

    /**
     * 未知角色（含**一个角色都没有**的账号）→ 恒假条件，一条工单都看不到。
     *
     * <p>改之前这条兜底是"落到学生口径"（ADR-012 的负面后果里记着它）。两种口径的差别不在
     * "谁的权限更大"，而在**出错时的表现方向**：漏配一个角色的可见范围，恒假会让基层立刻报
     * "我的列表空了"，学生口径则会安静地给出一批越权可见的单——后者的条件看起来还挺克制
     * （{@code student_id = 我}），评审时看不出问题。
     *
     * <p>用条件而不是抛异常也是刻意的：对调用方来说，"这个人确实没有单"和"这个人被拒绝"
     * 表现得一样（空列表），不会把一次数据配置疏忽升级成 500。
     */
    @Test
    void unknownRoleSeesNothing() {
        // 平台运营（PLATFORM）这类非租户角色若流到这里，看不到任何单
        assertThat(handler.buildScopedExpression(ticketTable(), 1L, TENANT, List.of("PLATFORM"), List.of()).toString())
                .isEqualTo("ticket.tenant_id = 1 AND 1 = 0");

        // 一个角色都没有的账号同样恒假（这正是"角色数据缺失"的样子）
        assertThat(handler.buildScopedExpression(ticketTable(), 5L, TENANT, List.of(), List.of()).toString())
                .isEqualTo("ticket.tenant_id = 1 AND 1 = 0");

        // 无租户的兜底分支也不放宽：仍然是一条也看不到
        assertThat(handler.buildScopedExpression(ticketTable(), 5L, null, List.of(), List.of()).toString())
                .isEqualTo("1 = 0");
    }

    /**
     * 多角色账号的优先级：ADMIN → WORKER → STUDENT → 拒绝。
     *
     * <p>钉住这个顺序是因为它有实际含义：一个人同时是学生和维修工时按**维修工**口径取数
     * （他确实要用维修工身份干活，列表里得有自己的派单）；把 STUDENT 提到前面会让他的
     * 派单凭空消失。加新角色分支时别把这个顺序改掉。
     */
    @Test
    void multipleRolesResolveInFixedOrder() {
        assertThat(handler.buildScopedExpression(ticketTable(), 9L, TENANT,
                List.of("STUDENT", "WORKER"), List.of(9L)).toString())
                .isEqualTo("ticket.tenant_id = 1 AND (ticket.building_id IN (9)"
                        + " OR ticket.worker_id = 9"
                        + " OR ticket.id IN (SELECT ticket_id FROM ticket_collaborator WHERE worker_id = 9))");

        // 后勤 + 学生：后勤口径（角色维度不限制，只剩租户条件）
        assertThat(handler.buildScopedExpression(ticketTable(), 9L, TENANT,
                List.of("STUDENT", "ADMIN"), List.of()).toString())
                .isEqualTo("ticket.tenant_id = 1");
    }
}
