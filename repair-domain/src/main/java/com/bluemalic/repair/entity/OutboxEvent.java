package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可靠事件发件箱（阶段 1 · Outbox 模式）。
 *
 * <p>业务表变更与这条事件在**同一个本地事务**里提交；relay 异步读取并投递。
 * 这样“工单状态已提交、通知/超时事件丢了”不会发生——失败只会重试，不会回滚业务。
 */
@Data
@TableName("outbox_event")
public class OutboxEvent {

    private Long id;

    private Long tenantId;

    /** 聚合类型，目前是 TICKET */
    private String aggregateType;

    private Long aggregateId;

    /** 事件类型，如 TICKET_STATUS_CHANGED */
    private String eventType;

    /** 事件唯一 ID；消费者按它做幂等 */
    private String eventId;

    /** JSON 文本；列类型是 MySQL JSON */
    private String payload;

    /** NEW / SENDING / SENT */
    private String status;

    private Integer attempts;

    /** 下次可处理时间：失败退避的到期点，或 claim 租约的到期点（见 OutboxRelayJob） */
    private LocalDateTime nextAttemptAt;

    private LocalDateTime createdAt;

    private LocalDateTime sentAt;
}
