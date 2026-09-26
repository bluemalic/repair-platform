package com.bluemalic.repair.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 拼给模型的提示词。
 *
 * <p><b>提示词里写什么、不写什么</b>：写"数据是什么样"（表结构、字段含义、统计口径）与
 * "输出要什么形状"（JSON），**不写安全约束**——那些由 {@link SqlSafetyGateway} 在代码里强制
 * （ADR-003 的立场：安全不能写在提示词里，因为提示词约束不了模型行为）。规则里虽然也提了
 * "只写单条 SELECT"，但它只是**提高一次通过率**，不是依赖它拦人。
 *
 * <p>口径那一段是必须的：AI 自己写 SQL，同一指标写出与统计看板不一致的数字会让整个系统失去信任
 * （见 ADR-003 的"落地时的修正"）。把口径喂给它不能保证一致，但能大幅减少差异——差异本身
 * 也写在了 docs/03 里，不假装它不存在。
 */
@Component
@RequiredArgsConstructor
public class SchemaPromptBuilder {

    private final SchemaCatalog schemaCatalog;

    /**
     * 系统提示词：角色 + 硬性规则 + 口径。
     *
     * <p>不含表结构——那是每次调用都要带上的"数据"，放在用户消息里
     * （见 {@link #userMessage}）；这样系统提示词是一段常量，也便于后面接流式时保持一致。
     */
    public String systemPrompt() {
        return """
                你是高校后勤报修系统的数据助手，把后勤管理员的问题变成一条 MySQL 查询。

                【硬性规则】
                1. 只写一条 SELECT 语句；不要 UNION、不要 WITH、不要分号、不要注释
                2. **任何位置都不要写子查询**：`FROM (SELECT ...)`、`WHERE id IN (SELECT ...)`、
                   `EXISTS (SELECT ...)`、`(SELECT COUNT(*) ...)` 都不允许。要表达"不在某个集合里"，
                   用 `LEFT JOIN 那张表 ON ... WHERE 右表主键 IS NULL`；要跨表比较，用 JOIN + GROUP BY
                3. 连接只用 `JOIN`（内连接）与 `LEFT JOIN`，不要 `RIGHT JOIN`；表名要写全、不要起别名；
                   多表查询时用 `表名.字段名` 限定
                4. 不要查 password、phone 这类字段
                5. 不要写 tenant_id 或 deleted 条件——这两个由系统自动加上，写了只会重复
                6. 不要写 LIMIT——系统会按行数上限截断

                【统计口径】（与统计看板保持一致，尽量按这个来）
                - 报修量 = 工单条数（COUNT(*)）
                - 工单状态：10 待派单、20 待接单、30 处理中、40 待验收（师傅已完工、等学生验收）、
                  50 已完成（学生已验收）、60 已关闭、70 已撤单、80 已驳回。
                  用户说"完成 / 完工 / 修好了"而没有更具体的要求时，按 status IN (40, 50, 60) 算
                  （三轮评测里这一条曾经每轮都不一样，是"口径没定义"而不是模型不会写——见 docs/07）
                - 超时工单 = ticket_log 里出现过 ACCEPT_TIMEOUT / PROCESS_TIMEOUT / AUTO_CLOSE 的工单
                - 房间号 ticket.room **只在楼栋内唯一**（不同楼栋会有一样的房间号）：
                  按房间统计时要同时带上楼栋，否则会把两个不同的房间合成一行
                - 响应时长用 ticket.arrive_minutes，处理时长用 ticket.handle_minutes（字段为空表示还没走到那一步）
                - 满意度 = ticket_evaluation.score 的平均值（1-5 分）
                - 时间范围请按用户说的算（"上月"= 上一个自然月）；用户没说就取近 30 天

                【输出】
                只输出一个 JSON 对象，不要额外解释：
                {"sql": "...", "chartType": "bar|line|pie|none", "xColumn": "维度列的别名", "yColumn": "数值列的别名"}
                - 列别名请用中文（如 `AS 报修量`），前端直接拿它当表头与图例
                - chartType：单维度对比用 bar，按时间趋势用 line，占比用 pie，只有一个数字（如总数）用 none
                """;
    }

    /** 用户消息：**表结构放在最前面**——它是每次调用都相同的大块内容，放前面也便于模型侧的上下文缓存。 */
    public String userMessage(String question) {
        return "【可查询的表】\n" + schemaCatalog.describe() + "\n\n【问题】\n" + question;
    }

    /** 出结论时的用户消息：带上问题与实际查询结果（模型没看到数据就写不出"共 42 单"这种结论）。 */
    public String conclusionUserMessage(String question, String rowsJson, boolean rowLimited) {
        return "【问题】\n" + question
                + "\n\n【查询结果】（JSON，已按行数上限截断：" + (rowLimited ? "是" : "否") + "）\n" + rowsJson;
    }

    /** 结论的系统提示词：要一句话、要基于数据、不要编。 */
    public String conclusionSystemPrompt() {
        return """
                你是高校后勤报修系统的数据助手。根据用户的问题和查询结果，用一两句中文说出结论。

                【要求】
                - 直接说结论，不要复述 SQL、不要罗列全部数据
                - 只依据给出的数据说话，数据里没有的不要推测
                - 如果结果为空，就说这次查询没有数据，不要编造
                - 如果结果被截断，可以顺带提一句"（结果已截断）"
                - 不要用 markdown 标题或列表，就是一兩句话
                """;
    }
}
