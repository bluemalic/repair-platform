package com.bluemalic.repair.channel;

import com.bluemalic.repair.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 站内信通道（阶段 4 · ADR-030）。
 *
 * <p><b>它不参与"外部投递"循环</b>：站内信由 {@code NotificationDeliveryService#deliverInApp}
 * 在业务事务里直接写入（那是事实来源，失败要回滚 + MQ 重投）。这里保留实现是为了让
 * SPI 有第一个真实实现（ADR-030 决定 2：首版只有 IN_APP），也方便将来"站内信也要重试"时接上。
 */
@Component
@RequiredArgsConstructor
public class InAppChannel implements NotificationChannel {

    public static final String TYPE = "IN_APP";

    private final NotificationService notificationService;

    @Override
    public String channelType() {
        return TYPE;
    }

    @Override
    public boolean isEnabled(long tenantId) {
        // 站内信永远是默认通道，没有"关掉"的开关（ADR-030 决定 1）
        return true;
    }

    @Override
    public void send(NotificationMessage message) {
        notificationService.sendOrThrow(message.tenantId(), message.receiverId(), message.type(),
                message.title(), message.content(), message.ticketId());
    }
}
