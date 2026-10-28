package com.bluemalic.repair.common;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.schema.Table;

/**
 * jsqlparser AST 的小工具。此前 {@code parenthesis}/{@code parenthesized} 与
 * {@code qualified} 在 TicketDataScopeHandler（数据权限）与 SqlSafetyGateway（AI 网关）
 * 各写了一份、一字不差——两处注释还都记录着同一个 jsqlparser 5.x 的坑，收敛到这里。
 */
public final class SqlAst {

    private SqlAst() {
    }

    /**
     * 包一层括号。jsqlparser 5.x 的 {@code Parenthesis} 没有"接收表达式"的构造器
     * （{@code withExpression} 是"替换第 0 个元素"，空列表上会 IndexOutOfBounds），
     * 所以先建空括号再 add。
     */
    public static Expression parenthesize(Expression expression) {
        Parenthesis parenthesis = new Parenthesis();
        parenthesis.add(expression);
        return parenthesis;
    }

    /** 条件列限定到表名或别名：JOIN 场景别名优先，避免与其他表同名列歧义。 */
    public static String qualified(Table table, String column) {
        String prefix = table.getAlias() != null ? table.getAlias().getName() : table.getName();
        return prefix + "." + column;
    }
}
