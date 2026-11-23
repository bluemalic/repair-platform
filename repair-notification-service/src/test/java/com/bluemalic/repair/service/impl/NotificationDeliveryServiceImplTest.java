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
 *   <li>同一 eventId 重复投递时靠唯一键与数据库原子租约挡住并发重复发送。</li>
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
        NotificationDelivery pending = pending("evt-2", 555L);
        when(deliveryMapper.selectOne(any())).thenReturn(pending);
        when(deliveryMapper.claimDue(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(deliveryMapper.completeClaim(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        service.scheduleExternal(message("evt-2"), 555L);
        service.deliverExternal(message("evt-2"));

        verify(deliveryMapper).completeClaim(eq(1L), eq(TENANT), any(), eq("FAILED"), eq(1),
                org.mockito.ArgumentMatchers.contains("boom"), any());
    }

    @Test
    void 重复事件读取已有SENT记录后跳过投递() {
        wecom.enabled = true;
        NotificationDelivery sent = pending("evt-3", 555L);
        sent.setStatus("SENT");
        when(deliveryMapper.selectOne(any())).thenReturn(sent);

        service.deliverExternal(message("evt-3"));

        assertThat(wecom.sent).isZero();
        verify(deliveryMapper, never()).claimDue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 外部工作项在消费事务内持久化并关联站内信() {
        wecom.enabled = true;
        when(deliveryMapper.insert(any(NotificationDelivery.class))).thenReturn(1);
        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);

        service.scheduleExternal(message("evt-3b"), 556L);

        verify(deliveryMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(captor.getValue().getNotificationId()).isEqualTo(556L);
    }

    @Test
    void 消费事务后从持久化PENDING工作项执行外部通道() {
        wecom.enabled = true;
        NotificationDelivery pending = pending("evt-3c", 557L);
        when(deliveryMapper.selectOne(any())).thenReturn(pending);
        when(deliveryMapper.claimDue(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(deliveryMapper.completeClaim(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        service.deliverExternal(message("evt-3c"));

        assertThat(wecom.sent).isEqualTo(1);
        verify(deliveryMapper).completeClaim(eq(1L), eq(TENANT), any(), eq("SENT"), eq(0), eq(null), any());
        verify(deliveryMapper, never()).insert(any(NotificationDelivery.class));
    }

    @Test
    void 数据库租约只允许一个并发工作进程发送() {
        wecom.enabled = true;
        NotificationDelivery pending = pending("evt-race", 558L);
        when(deliveryMapper.selectOne(any())).thenReturn(pending);
        when(deliveryMapper.claimDue(any(), any(), any(), any(), any(), any())).thenReturn(1, 0);
        when(deliveryMapper.completeClaim(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        service.deliverExternal(message("evt-race"));
        service.deliverExternal(message("evt-race"));

        assertThat(wecom.sent).isEqualTo(1);
        verify(deliveryMapper, org.mockito.Mockito.times(2)).claimDue(any(), any(), any(), any(), any(), any());
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
    void 重试遇到不可用通道时标记DEAD() {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setId(1L);
        delivery.setTenantId(TENANT);
        delivery.setEventId("evt-5");
        delivery.setChannel("REMOVED");
        delivery.setReceiverId(7L);
        delivery.setNotificationId(555L);
        delivery.setStatus("FAILED");
        delivery.setRetryCount(1);
        delivery.setNextRetryAt(java.time.LocalDateTime.now().minusMinutes(1));
        when(deliveryMapper.selectList(any())).thenReturn(List.of(delivery));
        when(deliveryMapper.claimDue(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(deliveryMapper.completeClaim(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        int done = service.retryDue(TENANT, 10);

        assertThat(done).isEqualTo(1);
        verify(deliveryMapper).completeClaim(eq(1L), eq(TENANT), any(), eq("DEAD"), eq(1), any(), any());
    }

    @Test
    void 重试时按站内信重建正文() {
        wecom.enabled = true;
        NotificationDelivery delivery = pending("evt-retry-body", 559L);
        delivery.setStatus("FAILED");
        delivery.setRetryCount(1);
        delivery.setNextRetryAt(java.time.LocalDateTime.now().minusMinutes(1).withNano(0));
        when(deliveryMapper.selectList(any())).thenReturn(List.of(delivery));
        when(deliveryMapper.claimDue(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(deliveryMapper.completeClaim(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        Notification notification = new Notification();
        notification.setType("TICKET_ASSIGNED");
        notification.setTitle("有新的工单");
        notification.setContent("请尽快接单");
        notification.setTicketId(99L);
        when(notificationMapper.selectById(559L)).thenReturn(notification);

        int attempted = service.retryDue(TENANT, 10);

        assertThat(attempted).isEqualTo(1);
        assertThat(wecom.lastMessage.content()).isEqualTo("请尽快接单");
        verify(deliveryMapper).completeClaim(eq(1L), eq(TENANT), any(), eq("SENT"), eq(1), eq(null), any());
    }

    private static NotificationMessage message(String eventId) {
        return new NotificationMessage(TENANT, eventId, 7L, "TICKET_ASSIGNED", "有新的工单", "请尽快接单", 9L);
    }

    private static NotificationDelivery pending(String eventId, long notificationId) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setId(1L);
        delivery.setTenantId(TENANT);
        delivery.setEventId(eventId);
        delivery.setChannel("WECOM");
        delivery.setReceiverId(7L);
        delivery.setNotificationId(notificationId);
        delivery.setStatus("PENDING");
        delivery.setRetryCount(0);
        delivery.setNextRetryAt(java.time.LocalDateTime.now().withNano(0));
        return delivery;
    }

    /** 测试用通道：不连外网，按开关决定是否"发送"。 */
    private static final class FakeChannel implements NotificationChannel {

        private final String type;

        private boolean enabled;

        private boolean fail;

        private int sent;

        private NotificationMessage lastMessage;

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
            lastMessage = message;
            if (fail) {
                throw new IllegalStateException("boom");
            }
            sent++;
        }
    }
}
