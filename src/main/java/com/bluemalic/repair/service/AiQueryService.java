package com.bluemalic.repair.service;

import com.bluemalic.repair.vo.AiQueryVO;

/**
 * AI 数据助手：一句自然语言 → 一条查询 → 数据 + 图 + 结论。
 *
 * <p>它编排的就是 ADR-003 那条链路：**生成 → 安全网关校验 → 只读执行 → 出结论**，
 * 四步各自在别的类里（{@code AiAssistant} / {@code SqlSafetyGateway} / {@code AiQueryExecutor}），
 * 这里只负责顺序与降级。
 *
 * <p>两个方法跑的是**同一条链路**，区别只有一个：中间结果（SQL、数据）要不要立刻回调出去，
 * 所以业务规则永远只有一份——不流式的那条路只是传了 {@link AiQueryProgress#NONE}。
 */
public interface AiQueryService {

    /**
     * 问一次数。
     *
     * @param question 后勤管理员的问题（自然语言）
     */
    AiQueryVO ask(String question);

    /**
     * 问一次数，并把**中间结果**分阶段回调给 {@code progress}（SSE 流式用，见 docs/03 §5.6）。
     *
     * <p><b>为什么租户要显式传</b>：流式的实际工作在别的线程上跑，那里没有 Sa-Token 上下文
     * （{@code StpUtil} 取不到登录态、Session 也读不到），租户必须由调用方在请求线程上取好再传进来。
     * 这个方法**不碰登录态**，也就不会把"线程里没有上下文"误判成"没登录"。
     *
     * @param tenantId 当前租户（由调用方在请求线程上解析）
     */
    AiQueryVO askStreaming(String question, long tenantId, AiQueryProgress progress);
}
