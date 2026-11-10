package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知投递记录（阶段 4 · ADR-030）。
 *
 * <p>站内信（{@code notification} 表）是事实来源，**不经过这张表**；这里记的是"同一个事件
 * 还往哪些外部通道发过、发成没发成"。所以 {@code IN_APP} 通道正常也不写这里
 * （{@code notification_id} 列留着，是用来把将来"需要回查站内信"的场景接上的）。
 *
 * <p>唯一键 {@code (event_id, channel, receiver_id)} 是去重的物理依据：重复消息第二次插入
 * 直接撞键，不需要先查后插（那在并发下必然漏）。
 */
@Data
@TableName("notification_delivery")
public class NotificationDelivery {

    private Long id;

    private Long tenantId;

    /** 来源事件 ID（outbox_event.event_id） */
    private String eventId;

    /** 通道：IN_APP / WECOM / SMS / EMAIL */
    private String channel;

    private Long receiverId;

    private Long notificationId;

    /** PENDING / SENT / FAILED / DEAD */
    private String status;

    private Integer retryCount;

    /** 下次可重试时间：指数退避的到期点 */
    private LocalDateTime nextRetryAt;

    /** 最后一次失败原因（已截断，绝不写密钥/手机号） */
    private String lastError;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
