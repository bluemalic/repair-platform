package com.bluemalic.repair.service;

import java.time.Instant;
import java.util.List;

/**
 * 超时调度（ADR-001：Redis ZSet 延迟队列，ZREM 返回值做多实例去重）。
 *
 * <p>职责边界：本接口只负责"登记到期任务 / 消费到期任务 / 兜底扫描"这些**机制**，
 * 到期的工单由谁处理（自动关闭）在调用方（TimeoutScheduler）里编排——这样不被
 * TicketService 反向依赖，避免两个 Service 互相引用形成循环依赖。
 */
public interface TimeoutService {

    /** 工单进入 50 已完成时登记验收超时：到期时间 = 现在 + 验收阈值（默认 24h）。 */
    void registerEval(long ticketId);

    /** 显式指定到期时间登记（测试与兜底补录用）。 */
    void registerEvalDeadline(long ticketId, Instant deadline);

    /** 取消该工单的全部超时登记（到达终态或人工关闭时调用）。 */
    void cancel(long ticketId);

    /**
     * 消费到期任务：取 score ≤ now 的成员，逐个 ZREM —— 只有删除成功的实例才把该工单
     * 放进返回列表（多实例并发下天然只有一个执行者）。
     */
    List<Long> handleDueEval();

    /** 兜底：直接扫库找"该到期却没在 ZSet 里"的工单（Redis 重启丢任务场景）。 */
    List<Long> backstopScanEval();
}