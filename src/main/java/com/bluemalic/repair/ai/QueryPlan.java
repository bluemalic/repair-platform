package com.bluemalic.repair.ai;

/**
 * 模型的产出：一条 SQL + 怎么展示它的建议。
 *
 * <p>做成 record 是因为它同时是**结构化输出**的目标类型——LangChain4j 会按它的字段生成 JSON schema
 * 提示模型照此作答，再把回复反序列化回来（字段名即 JSON 键名）。
 *
 * @param sql       单条 SELECT（还没过安全网关）
 * @param chartType bar / line / pie / none
 * @param xColumn   维度列的别名（对应 SQL 里的 {@code AS}）
 * @param yColumn   数值列的别名
 */
public record QueryPlan(String sql, String chartType, String xColumn, String yColumn) {
}
