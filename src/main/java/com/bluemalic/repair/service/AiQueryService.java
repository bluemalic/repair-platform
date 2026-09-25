package com.bluemalic.repair.service;

import com.bluemalic.repair.vo.AiQueryVO;

/**
 * AI 数据助手：一句自然语言 → 一条查询 → 数据 + 图 + 结论。
 *
 * <p>它编排的就是 ADR-003 那条链路：**生成 → 安全网关校验 → 只读执行 → 出结论**，
 * 四步各自在别的类里（{@code AiAssistant} / {@code SqlSafetyGateway} / {@code AiQueryExecutor}），
 * 这里只负责顺序与降级。
 */
public interface AiQueryService {

    /**
     * 问一次数。
     *
     * @param question 后勤管理员的问题（自然语言）
     */
    AiQueryVO ask(String question);
}
