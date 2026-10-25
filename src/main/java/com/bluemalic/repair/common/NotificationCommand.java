package com.bluemalic.repair.common;

/**
 * 站内信投递命令（Outbox payload）。
 *
 * <p>{@code sync} 模式下由 {@code TicketNoticePublisher} 直接调用 NotificationService；
 * {@code mq} 模式下写进 Outbox，再由 MQ 消费者调用同一个 NotificationService。
 */
public record NotificationCommand(Long tenantId, Long receiverId, String type,
                                  String title, String content, Long ticketId) {

    public static final String EVENT_TYPE = "TICKET_NOTIFICATION";
}
