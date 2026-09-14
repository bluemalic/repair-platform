package com.bluemalic.repair.interceptor;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.schema.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据权限条件构建的单元测试：不依赖 Spring / 登录态，专测 {@code buildScopeExpression} 输出的 SQL。
 *
 * <p>核心断言是"条件列限定到表名/别名"（A2 还债）：单表时是 {@code ticket.student_id}，
 * 带别名 JOIN 时是 {@code t.student_id}——否则统计看板（M3）的多表查询会列歧义。
 */
class TicketDataScopeHandlerTest {

    private final TicketDataScopeHandler handler = new TicketDataScopeHandler(null, null);

    private Table ticketTable() {
        return new Table("ticket");
    }

    private Table ticketAliased(String alias) {
        Table table = new Table("ticket");
        table.setAlias(new Alias(alias));
        return table;
    }

    @Test
    void studentScopeIsQualifiedByTableNameOrAlias() {
        assertThat(handler.buildScopeExpression(ticketTable(), 3L, List.of("STUDENT"), List.of()).toString())
                .isEqualTo("ticket.student_id = 3");
        assertThat(handler.buildScopeExpression(ticketAliased("t"), 3L, List.of("STUDENT"), List.of()).toString())
                .isEqualTo("t.student_id = 3");
    }

    @Test
    void workerScopeUsesQualifiedBuildingIn() {
        assertThat(handler.buildScopeExpression(ticketAliased("t"), 2L, List.of("WORKER"), List.of(1L, 3L)).toString())
                .isEqualTo("t.building_id IN (1, 3)");
    }

    @Test
    void workerWithoutBuildingsSeesNothing() {
        assertThat(handler.buildScopeExpression(ticketTable(), 2L, List.of("WORKER"), List.of()).toString())
                .isEqualTo("1 = 0");
    }

    @Test
    void adminIsUnrestricted() {
        assertThat(handler.buildScopeExpression(ticketTable(), 1L, List.of("ADMIN"), List.of())).isNull();
    }
}