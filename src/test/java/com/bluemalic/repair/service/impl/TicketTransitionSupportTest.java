package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.MetricsRecorder;
import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.common.TicketStatusChangedEvent;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.RepairCodeMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketCollaboratorMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.bluemalic.repair.service.NotificationService;
import com.bluemalic.repair.service.OutboxService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TicketTransitionSupportTest {

    @Test
    void transferNoticeWithNoWorkerIsSkippedInsteadOfThrowing() {
        NotificationService notificationService = mock(NotificationService.class);
        TicketTransitionSupport support = support(
                mock(TicketMapper.class), mock(TicketLogMapper.class),
                notificationService, mock(OutboxService.class));

        Ticket ticket = new Ticket();
        ticket.setTenantId(1L);
        ticket.setStudentId(2L);
        ticket.setTicketNo("WX20260101001");
        // workerId 为 null：转派 / 管理员驳回清空主责后可能出现。
        // 修复前这里对 Long 拆箱会 NPE，接口报 500；修复后应静默跳过。
        support.notifyTransition(ticket, TicketAction.TRANSFER, null, "已转给新师傅");

        verifyNoInteractions(notificationService);
    }

    @Test
    void conditionalUpdateAppendsOutboxEventAfterSuccessfulUpdate() {
        TicketMapper ticketMapper = mock(TicketMapper.class);
        when(ticketMapper.update(any(), any())).thenReturn(1);
        OutboxService outboxService = mock(OutboxService.class);
        TicketTransitionSupport support = support(
                ticketMapper, mock(TicketLogMapper.class),
                mock(NotificationService.class), outboxService);

        support.conditionalUpdate(123L, 20, TicketAction.DISPATCH, 9L, 1L, 10,
                wrapper -> { }, entity -> { });

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).append(eq(1L), eq("TICKET"), eq(123L),
                eq(TicketStatusChangedEvent.EVENT_TYPE), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(TicketStatusChangedEvent.class);
        TicketStatusChangedEvent event = (TicketStatusChangedEvent) payload.getValue();
        assertThat(event.ticketId()).isEqualTo(123L);
        assertThat(event.fromStatus()).isEqualTo(10);
        assertThat(event.toStatus()).isEqualTo(20);
        assertThat(event.action()).isEqualTo("DISPATCH");
        assertThat(event.operatorId()).isEqualTo(9L);
    }

    private TicketTransitionSupport support(TicketMapper ticketMapper, TicketLogMapper ticketLogMapper,
                                            NotificationService notificationService, OutboxService outboxService) {
        return new TicketTransitionSupport(
                ticketMapper,
                mock(BuildingMapper.class),
                mock(RepairCodeMapper.class),
                mock(SysUserMapper.class),
                mock(TicketCategoryMapper.class),
                mock(TicketCollaboratorMapper.class),
                mock(WorkerBuildingMapper.class),
                mock(StringRedisTemplate.class),
                ticketLogMapper,
                notificationService,
                mock(MetricsRecorder.class),
                outboxService);
    }
}
