package com.bluemalic.repair.ai;

import java.util.List;

/**
 * 一次只读查询的结果：列名、行、以及"是否因为行数上限被截断"。
 *
 * <p>{@code columns} 用的是 {@code getColumnLabel}——也就是 SQL 里 {@code AS} 出来的别名，
 * 正是模型给结果起的名字（"类别""报修量"这种），直接拿去当表格表头与图表轴名。
 *
 * <p>{@code rows} 里的数字统一是 {@link java.math.BigDecimal}（理由见
 * {@code AiQueryExecutor#normalize}）：本项目的全局规则会把 {@code Long} 序列化成字符串
 * （雪花 ID 防精度丢失），而问数结果里的数字要给图用，必须是数字。
 */
public record AiQueryResult(List<String> columns, List<List<Object>> rows, boolean rowLimited) {
}
