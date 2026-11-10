package com.bluemalic.repair.service;

/**
 * 超时任务的 MQ 载荷（阶段 2 · ADR-024）。
 *
 * <p>三个节点与 {@code TimeoutScheduler} 的动作一一对应：
 * {@link #NODE_ACCEPT} → 未接单提醒、{@link #NODE_PROCESS} → 处理超时升级、
 * {@link #NODE_EVAL} → 验收超时自动关闭。
 *
 * <p>只带"哪个租户的哪张工单 + 哪个节点"，不带截止时间：到期与否由消息自身的投递时间决定，
 * 是否还该执行由处理器在租户库里按状态 + ticket_log 判重（幂等），
 * 所以重复投递/迟到投递都不会造成重复动作。
 */
public record TimeoutCommand(long tenantId, long ticketId, String node) {

    public static final String NODE_ACCEPT = "ACCEPT";
    public static final String NODE_PROCESS = "PROCESS";
    public static final String NODE_EVAL = "EVAL";
}
