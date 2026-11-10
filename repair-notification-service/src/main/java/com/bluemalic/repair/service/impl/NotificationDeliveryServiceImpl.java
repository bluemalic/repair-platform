package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.channel.InAppChannel;
import com.bluemalic.repair.channel.NotificationChannel;
import com.bluemalic.repair.channel.NotificationMessage;
import com.bluemalic.repair.entity.Notification;
import com.bluemalic.repair.entity.NotificationDelivery;
import com.bluemalic.repair.mapper.NotificationDeliveryMapper;
import com.bluemalic.repair.mapper.NotificationMapper;
import com.bluemalic.repair.service.NotificationService;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 通知投递编排（阶段 4 · ADR-030）。
 *
 * <p>两段严格分开：
 * <ol>
 *   <li>{@link #deliverInApp} —— **在业务事务内**：写站内信（事实来源）+ 记一条 IN_APP 投递记录。
 *       失败要抛出，让 MQ 重投；这一步不能有任何外部网络调用。</li>
 *   <li>{@link #deliverExternal} —— **在事务提交之后**：逐个启用中的外部通道投递，
 *       每条写自己的 delivery 行。失败只回填 FAILED/DEAD，绝不影响已经提交的站内信。</li>
 * </ol>
 *
 * <p>为什么不做成"一个循环里先站内信再外部"：外部接口超时会把业务事务拖长到秒级，
 * 而这个事务里有工单状态的条件更新——长事务是并发问题的源头。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDeliveryServiceImpl {

    /** 重试上限：超过进 DEAD，由告警盯 DEAD 的数量（ADR-030 决定 5）。 */
    static final int MAX_RETRY = 5;

    /** 退避基准：第 n 次失败后等 30s × 2^(n-1)，封顶 30 分钟。 */
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);

    private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

    /** 失败原因列的截断长度（与 DDL 的 varchar(500) 对齐）。 */
    private static final int LAST_ERROR_LIMIT = 400;

    private final NotificationService notificationService;

    private final NotificationDeliveryMapper deliveryMapper;

    /** 重试时从站内信取回正文：delivery 表只存"投递状态"，正文不复制第二份。 */
    private final NotificationMapper notificationMapper;

    private final TenantSchemaCatalog schemaCatalog;

    private final List<NotificationChannel> channels;

    /** 通道码 → 实现；重试时按 delivery 行里的 channel 找回来。 */
    private Map<String, NotificationChannel> channelIndex;

    /** 事务内调用：站内信必须成功，同时记一条 IN_APP 投递记录。 */
    public Long deliverInApp(NotificationMessage message) {
        Long notificationId = notificationService.sendOrThrow(message.tenantId(), message.receiverId(),
                message.type(), message.title(), message.content(), message.ticketId());
        if (notificationId == null) {
            // 没有收件人：调用方传了空 receiver，站内信本来就不该写，这里也不再记投递
            return null;
        }
        NotificationDelivery delivery = newDelivery(message, InAppChannel.TYPE);
        delivery.setNotificationId(notificationId);
        delivery.setStatus(DeliveryStatus.SENT.name());
        delivery.setRetryCount(0);
        insertIgnoreDuplicate(delivery);
        return notificationId;
    }

    /** 事务提交后调用：外部通道逐条投递，失败进 FAILED 等重试。 */
    public void deliverExternal(NotificationMessage message) {
        if (message.eventId() == null || message.eventId().isBlank()) {
            // 没有事件 ID 就无法去重，宁可只发站内信，也不要制造一堆重复外部消息
            log.debug("通知没有 eventId，跳过外部通道 receiver={} type={}",
                    message.receiverId(), message.type());
            return;
        }
        for (NotificationChannel channel : channels) {
            if (InAppChannel.TYPE.equals(channel.channelType())) {
                continue;
            }
            if (!channel.isEnabled(message.tenantId())) {
                continue;
            }
            deliverOne(channel, message);
        }
    }

    /**
     * 重试一批到期的投递（由 {@code NotificationDeliveryRetryJob} 调，**在某个租户库的上下文里**）。
     *
     * @return 本轮尝试的条数
     */
    public int retryDue(int limit) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 这里用字符串列名而不是 lambda：重试逻辑是纯编排，值得能在没有 MyBatis 上下文的
        // 单元测试里跑（lambda 查询需要实体的 TableInfo 缓存，纯单测会直接抛）。
        List<NotificationDelivery> due = deliveryMapper.selectList(
                Wrappers.<NotificationDelivery>query()
                        .in("status", DeliveryStatus.FAILED.name(), DeliveryStatus.PENDING.name())
                        .le("next_retry_at", now)
                        .orderByAsc("next_retry_at")
                        .last("LIMIT " + limit));
        if (due.isEmpty()) {
            return 0;
        }
        Map<String, NotificationChannel> index = channelIndex();
        for (NotificationDelivery delivery : due) {
            NotificationChannel channel = index.get(delivery.getChannel());
            if (channel == null || !channel.isEnabled(delivery.getTenantId())) {
                // 通道被关掉了（或实现被移除）：直接 DEAD，不让它永远占着重试队列
                markDead(delivery, "通道不可用：" + delivery.getChannel());
                continue;
            }
            NotificationMessage message = messageOf(delivery);
            if (message == null) {
                markDead(delivery, "站内信已不存在，无法重建投递内容");
                continue;
            }
            attempt(channel, delivery, message);
        }
        return due.size();
    }

    /**
     * 重试时重建投递内容：正文从站内信取（{@code notification_delivery} 不复制正文，
     * 否则同一句话在库里存两份、改一处就漂）。
     */
    private NotificationMessage messageOf(NotificationDelivery delivery) {
        if (delivery.getNotificationId() == null) {
            return null;
        }
        Notification notification = notificationMapper.selectById(delivery.getNotificationId());
        if (notification == null) {
            return null;
        }
        return new NotificationMessage(delivery.getTenantId(), delivery.getEventId(),
                delivery.getReceiverId(), notification.getType(), notification.getTitle(),
                notification.getContent(), notification.getTicketId());
    }

    private void deliverOne(NotificationChannel channel, NotificationMessage message) {
        NotificationDelivery delivery = newDelivery(message, channel.channelType());
        delivery.setStatus(DeliveryStatus.PENDING.name());
        delivery.setRetryCount(0);
        if (!insertIgnoreDuplicate(delivery)) {
            log.debug("该事件已投递过，跳过 channel={} eventId={} receiver={}",
                    channel.channelType(), message.eventId(), message.receiverId());
            return;
        }
        attempt(channel, delivery, message);
    }

    private void attempt(NotificationChannel channel, NotificationDelivery delivery, NotificationMessage message) {
        try {
            channel.send(message);
            delivery.setStatus(DeliveryStatus.SENT.name());
            delivery.setLastError(null);
            delivery.setNextRetryAt(LocalDateTime.now().withNano(0));
            deliveryMapper.updateById(delivery);
        } catch (RuntimeException e) {
            fail(delivery, e);
        }
    }

    private void fail(NotificationDelivery delivery, RuntimeException e) {
        int retry = delivery.getRetryCount() == null ? 0 : delivery.getRetryCount();
        int next = retry + 1;
        delivery.setRetryCount(next);
        delivery.setLastError(truncate(e.getClass().getSimpleName() + ": " + e.getMessage()));
        if (next >= MAX_RETRY) {
            delivery.setStatus(DeliveryStatus.DEAD.name());
            delivery.setNextRetryAt(LocalDateTime.now().withNano(0));
            log.error("通知投递进入死信 channel={} eventId={} receiver={} retry={}",
                    delivery.getChannel(), delivery.getEventId(), delivery.getReceiverId(), next, e);
        } else {
            delivery.setStatus(DeliveryStatus.FAILED.name());
            delivery.setNextRetryAt(LocalDateTime.now().withNano(0).plus(backoff(next)));
            log.warn("通知投递失败，{} 后重试 channel={} eventId={} receiver={} retry={}",
                    backoff(next), delivery.getChannel(), delivery.getEventId(),
                    delivery.getReceiverId(), next);
        }
        deliveryMapper.updateById(delivery);
    }

    private void markDead(NotificationDelivery delivery, String reason) {
        delivery.setStatus(DeliveryStatus.DEAD.name());
        delivery.setLastError(truncate(reason));
        deliveryMapper.updateById(delivery);
    }

    private NotificationDelivery newDelivery(NotificationMessage message, String channel) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setTenantId(message.tenantId());
        delivery.setEventId(message.eventId());
        delivery.setChannel(channel);
        delivery.setReceiverId(message.receiverId());
        delivery.setRetryCount(0);
        delivery.setNextRetryAt(now);
        delivery.setCreateTime(now);
        delivery.setUpdateTime(now);
        return delivery;
    }

    /**
     * 插入并忽略唯一键冲突。
     *
     * @return true = 本次确实插入了（= 这是第一次投递）；false = 已经存在（重复事件）
     */
    private boolean insertIgnoreDuplicate(NotificationDelivery delivery) {
        try {
            deliveryMapper.insert(delivery);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private static Duration backoff(int retry) {
        Duration delay = BASE_BACKOFF.multipliedBy(1L << Math.min(retry - 1, 10));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= LAST_ERROR_LIMIT ? text : text.substring(0, LAST_ERROR_LIMIT);
    }

    private Map<String, NotificationChannel> channelIndex() {
        if (channelIndex == null) {
            channelIndex = channels.stream()
                    .collect(Collectors.toMap(NotificationChannel::channelType, Function.identity(),
                            (a, b) -> a));
        }
        return channelIndex;
    }

    /** 投递状态。与 {@code notification_delivery.status} 的取值一一对应。 */
    enum DeliveryStatus {
        PENDING, SENT, FAILED, DEAD
    }
}
