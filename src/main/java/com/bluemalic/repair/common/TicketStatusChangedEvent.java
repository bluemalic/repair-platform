package com.bluemalic.repair.common;

/**
 * 工单状态变更事件（Outbox 载荷）。
 *
 * @param ticketId   工单 ID
 * @param fromStatus 变更前状态；首次提交为 null
 * @param toStatus   变更后状态
 * @param action     动作码，如 DISPATCH / ACCEPT / FINISH
 * @param operatorId 操作人 ID；系统任务为 0
 */
public record TicketStatusChangedEvent(long ticketId, Integer fromStatus, int toStatus,
                                       String action, long operatorId) {

    public static final String EVENT_TYPE = "TICKET_STATUS_CHANGED";
}
