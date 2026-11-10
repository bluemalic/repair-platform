package com.bluemalic.repair.service;

import java.util.List;

/**
 * 超时的**消费与扫描端口**（阶段 3.4 · ADR-035）：只有超时域用它。
 *
 * <p>与 {@link TimeoutRegistrar} 的分工：这里回答"谁到期了"，登记侧回答"什么时候到期"。
 * 拆开之后工单模块不必知道扫描的存在，超时模块也不必知道工单命令的存在。
 */
public interface TimeoutScanner {

    /** 消费到期的接单提醒：ZREM 成功者才进返回列表（多实例唯一消费）。 */
    List<DueTicket> handleDueAccept();

    /** 消费到期的未处理升级：同上。 */
    List<DueTicket> handleDueProcess();

    /** 消费到期的验收超时：同上。 */
    List<DueTicket> handleDueEval();

    /**
     * 兜底：直接扫库找"该提醒接单、却没在 ZSet 里"的工单（Redis 重启丢任务场景）。
     *
     * <p>扫的是**按分片选出的库**（平台库 + 属于该分片的学校库）：一个库一个租户上下文
     * （ADR-021 第 3 步），返回的每条都带着"该去哪个租户处理"。
     *
     * @param shardIndex 分片序号（0 基）
     * @param shardTotal 分片总数（单实例传 1）
     */
    List<DueTicket> backstopScanAccept(int shardIndex, int shardTotal);

    /** 兜底：直接扫库找"该升级却没在 ZSet 里"的处理中超期工单。 */
    List<DueTicket> backstopScanProcess(int shardIndex, int shardTotal);

    /** 兜底：直接扫库找"该到期却没在 ZSet 里"的已完成工单。 */
    List<DueTicket> backstopScanEval(int shardIndex, int shardTotal);

    /**
     * 单实例版本（不分片）：scheduled 模式与测试用它。
     *
     * <p>分片的用意只有多实例调度时才有意义（XXL-JOB 把 shardTotal 个分片分给不同执行器）；
     * 单实例轮询非要传 (0, n) 只会让每所学校每分钟少扫几次。
     */
    default List<DueTicket> backstopScanAccept() {
        return backstopScanAccept(0, 1);
    }

    default List<DueTicket> backstopScanProcess() {
        return backstopScanProcess(0, 1);
    }

    default List<DueTicket> backstopScanEval() {
        return backstopScanEval(0, 1);
    }
}
