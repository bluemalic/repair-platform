package com.bluemalic.repair.ai.agent;

/**
 * 轨迹里的一步：调了哪个工具、花了多久、拿到什么（摘要）。
 *
 * <p>留着它有三个用处：<b>演示时能看出模型真的在调工具</b>（而不是在编）、排查时能定位
 * "是哪一步开始跑偏"、评测时能统计工具调用次数（`docs/07` 的 Agent 子集）。
 *
 * <p>{@code summary} 是**结果摘要**（如"返回 6 个类别"），不是完整结果；**用户原文不进轨迹**，
 * 与 AGENTS §5.8 一致。
 */
public record AgentTraceStep(String tool, long ms, String summary, boolean rejected) {

    static AgentTraceStep ok(String tool, long ms, String summary) {
        return new AgentTraceStep(tool, ms, summary, false);
    }

    static AgentTraceStep rejected(String tool, String reason) {
        return new AgentTraceStep(tool, 0L, reason, true);
    }
}
