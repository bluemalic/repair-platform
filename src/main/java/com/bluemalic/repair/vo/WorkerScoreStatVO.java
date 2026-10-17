package com.bluemalic.repair.vo;

import lombok.Data;

/**
 * 派单选候选时用的"每人一份"统计（阶段 3 · ADR-019）。
 *
 * <p>**一次查询同时取负载与满意度**：左边是进行中工单数（20/30/40），右边是历史评价均分
 * （`ticket_evaluation` 与工单 1:1，不会让行数翻倍）。分两条 SQL 也能做，但同一批候选人
 * 查两次库没有收益——而且这里最容易写成 N+1，批量取是必须的。
 *
 * <p>它不是接口出参（接口出参是 {@link DispatchCandidateVO}），只是 SQL 结果的承载类型；
 * 放在 vo 包是沿用 {@code StatisticsMapper} 直接返回 VO 的既有做法。
 */
@Data
public class WorkerScoreStatVO {

    private Long workerId;

    /** 当前进行中（待接单/处理中/待验收）的工单数 */
    private Integer activeCount;

    /** 历史平均评分（1~5）；没有评价时为 null——**不要当成 0 分** */
    private Double avgScore;
}
