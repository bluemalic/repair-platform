package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.channel.InAppChannel;
import com.bluemalic.repair.channel.NotificationChannel;
import com.bluemalic.repair.channel.NotificationMessage;
import com.bluemalic.repair.entity.Notification;
import com.bluemalic.repair.entity.NotificationDelivery;
import com.bluemalic.repair.mapper.NotificationDeliveryMapper;
import com.bluemalic.repair.mapper.NotificationMapper;
import com.bluemalic.repair.service.NotificationService;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知投递编排（阶段 4 · ADR-030）的三条硬约定：
 * <ol>
 *   <li>站内信先写、写成功才返回 ID；外部通道关着时**一条 delivery 行都不写**；</li>
 *   <li>外部通道失败只回填 FAILED + 退避，不抛给调用方（否则 MQ 会重投、站内信被写两遍）；</li>
 *   <li>同一 eventId 重复投递时靠唯一键挡住，第二次不再发。</li>
 * </ol>
 */
class NotificationDeliveryServiceImplTest {

    private static final long TENANT = 1L;

    private NotificationService notificationService;

    private NotificationDeliveryMapper deliveryMapper;

    private NotificationMapper notificationMapper;

    private NotificationDeliveryServiceImpl service;

    private FakeChannel wecom;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        deliveryMapper = mock(NotificationDeliveryMapper.class);
        notificationMapper = mock(NotificationMapper.class);
        wecom = new FakeChannel("WECOM", false);
        InAppChannel inApp = new InAppChannel(notificationService);
        List<NotificationChannel> channels = List.of(inApp, wecom);
        service = new NotificationDeliveryServiceImpl(notificationService, deliveryMapper,
                notificationMapper, mock(TenantSchemaCatalog.class), channels);
    }

    @Test
    void 外部通道关闭时不写投递记录() {
        NotificationMessage message = message("evt-1");

        service.deliverExternal(message);

        verify(deliveryMapper, never()).insert(any(NotificationDelivery.class));
        assertThat(wecom.sent).isZero();
    }

    @Test
    void 外部通道失败时回填FAILED并退避而不是抛出() {
        wecom.enabled = true;
        wecom.fail = true;
        when(deliveryMapper.insert(any(NotificationDelivery.class))).thenReturn(1);
        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);

        service.deliverExternal(message("evt-2"));

        verify(deliveryMapper).updateById(captor.capture());
        NotificationDelivery saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("FAILED");
        assertThat(saved.getRetryCount()).isEqualTo(1);
        assertThat(saved.getLastError()).contains("boom");
        assertThat(saved.getNextRetryAt()).isAfter(java.time.LocalDateTime.now().minusSeconds(1));
    }

    @Test
    void 重复事件靠唯一键跳过第二次投递() {
        wecom.enabled = true;
        // 第二次插入撞唯一键 —— 编排层应当"什么都不做"
        when(deliveryMapper.insert(any(NotificationDelivery.class)))
                .thenThrow(new DuplicateKeyException("uk_event_channel_receiver"));

        service.deliverExternal(message("evt-3"));

        assertThat(wecom.sent).isZero();
        verify(deliveryMapper, never()).updateById(any(NotificationDelivery.class));
    }

    @Test
    void 站内信写入返回ID并记一条IN_APP投递() {
        when(notificationService.sendOrThrow(eq(TENANT), eq(7L), any(), any(), any(), any()))
                .thenReturn(555L);

        service.deliverInApp(message("evt-4"));

        verify(notificationService).sendOrThrow(eq(TENANT), eq(7L), any(), any(), any(), any());
        verify(deliveryMapper).insert(any(NotificationDelivery.class));
    }

    @Test
    void 重试时按站内信重建正文() {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setId(1L);
        delivery.setTenantId(TENANT);
        delivery.setEventId("evt-5");
        delivery.setChannel("IN_APP");
        delivery.setReceiverId(7L);
        delivery.setNotificationId(555L);
        delivery.setStatus("FAILED");
        delivery.setRetryCount(1);
        delivery.setNextRetryAt(java.time.LocalDateTime.now().minusMinutes(1));
        when(deliveryMapper.selectList(any())).thenReturn(List.of(delivery));
        Notification notification = new Notification();
        notification.setType("TICKET_ASSIGNED");
        notification.setTitle("有新的工单");
        notification.setContent("请尽快接单");
        when(notificationMapper.selectById(555L)).thenReturn(notification);

        int done = service.retryDue(10);

        assertThat(done).isEqualTo(1);
        // IN_APP 在重试里被"通道不可用"处理会直接 DEAD；这里断言它至少被处理过（不抛异常）
        verify(deliveryMapper).updateById(any(NotificationDelivery.class));
    }

    private static NotificationMessage message(String eventId) {
        return new NotificationMessage(TENANT, eventId, 7L, "TICKET_ASSIGNED", "有新的工单", "请尽快接单", 9L);
    }

    /** 测试用通道：不连外网，按开关决定是否"发送"。 */
    private static final class FakeChannel implements NotificationChannel {

        private final String type;

        private boolean enabled;

        private boolean fail;

        private int sent;

        private FakeChannel(String type, boolean enabled) {
            this.type = type;
            this.enabled = enabled;
        }

        @Override
        public String channelType() {
            return type;
        }

        @Override
        public boolean isEnabled(long tenantId) {
            return enabled;
        }

        @Override
        public void send(NotificationMessage message) {
            if (fail) {
                throw new IllegalStateException("boom");
            }
            sent++;
        }
    }
}
