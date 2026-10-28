package com.bluemalic.repair.common;

import com.bluemalic.repair.common.TicketAction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 业务指标（阶段 5 · ADR-020）。
 *
 * <p><b>为什么只记两类</b>：指标的价值在于"能在不看日志的情况下回答一个运维问题"。
 * 这里要回答的两个问题是：**平台在发生多少业务动作**（工单事件）
 * 和**AI 到底花了多少次调用**（AI 调用，直接对应成本）。其余细节在日志与统计看板里，
 * 不再重复造指标——指标越多，看板越没人看。
 *
 * <p><b>标签基数</b>：`event` 是封闭枚举（动作种类固定）；`tenant` 的数量 = 学校数量（个位数到几十），
 * 两者都不会把时间序列撑爆。**不要**把工单号、用户 ID 这类高基数维度加进标签——
 * 那是时序数据库最常见的自杀方式。
 *
 * <p>指标名用 Micrometer 的 `a.b.c` 形式，暴露到 Prometheus 时自动变成 `a_b_c_total`：
 * <ul>
 *   <li>{@code repair.ticket.events{event=...,tenant=...}} → {@code repair_ticket_events_total}</li>
 *   <li>{@code repair.ai.calls{kind=...,result=...}} → {@code repair_ai_calls_total}</li>
 * </ul>
 */
@Slf4j
@Component
public class MetricsRecorder {

    private final MeterRegistry registry;

    public MetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 一次成功的工单状态跃迁。只在**落库成功之后**记（并发失败、状态不允许都不该算"发生了一次"）。
     */
    public void ticketEvent(TicketAction action, long tenantId) {
        try {
            Counter.builder("repair.ticket.events")
                    .description("工单状态跃迁次数（按动作与租户）")
                    .tag("event", action.name())
                    .tag("tenant", String.valueOf(tenantId))
                    .register(registry)
                    .increment();
        } catch (RuntimeException e) {
            // 指标不该影响业务：注册失败（比如 registry 已关闭）只记日志
            log.warn("记录工单指标失败 action={} err={}", action, e.getMessage());
        }
    }

    /**
     * 一次模型调用（成功 / 失败各计一次）。
     *
     * @param kind provider.call 里传的用途名（"问数" / "Agent 编排" / "维修建议"）
     */
    public void aiCall(String kind, boolean ok) {
        try {
            Counter.builder("repair.ai.calls")
                    .description("模型调用次数（按用途与结果）")
                    .tag("kind", kind)
                    .tag("result", ok ? "ok" : "failed")
                    .register(registry)
                    .increment();
        } catch (RuntimeException e) {
            log.warn("记录 AI 指标失败 kind={} err={}", kind, e.getMessage());
        }
    }
}
