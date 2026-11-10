package com.bluemalic.repair.channel;

/**
 * 一条待投递的通知（阶段 4 · ADR-030）。
 *
 * <p>它是通道无关的：站内信、企业微信、短信拿到的都是同一份内容，
 * 差别只在"怎么发"。{@code eventId} 是去重键（与 outbox 事件一一对应），
 * 没有它就无法判断"这条是不是已经发过了"。
 */
public record NotificationMessage(
        long tenantId,
        String eventId,
        long receiverId,
        String type,
        String title,
        String content,
        Long ticketId) {
}
