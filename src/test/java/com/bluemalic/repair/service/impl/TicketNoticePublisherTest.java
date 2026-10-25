package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.NotificationCommand;
import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.service.NotificationService;
import com.bluemalic.repair.service.OutboxService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TicketNoticePublisherTest {

    @Test
    void transferNoticeWithNoWorkerIsSkippedInsteadOfThrowing() {
        NotificationService notificationService = mock(NotificationService.class);
        TicketNoticePublisher publisher = new TicketNoticePublisher(
                notificationService, mock(OutboxService.class), "sync");

        Ticket ticket = new Ticket();
        ticket.setTenantId(1L);
        ticket.setStudentId(2L);
        ticket.setTicketNo("WX20260101001");
        // workerId 为 null：转派 / 管理员驳回清空主责后可能出现。
        // 修复前这里对 Long 拆箱会 NPE，接口报 500；修复后应静默跳过。
        publisher.notifyTransition(ticket, TicketAction.TRANSFER, null, "已转给新师傅");

        verifyNoInteractions(notificationService);
    }

    @Test
    void mqModeAppendsNotificationCommandInsteadOfSending() {
        NotificationService notificationService = mock(NotificationService.class);
        OutboxService outboxService = mock(OutboxService.class);
        TicketNoticePublisher publisher = new TicketNoticePublisher(
                notificationService, outboxService, "mq");

        Ticket ticket = new Ticket();
        ticket.setId(99L);
        ticket.setTenantId(1L);
        ticket.setStudentId(2L);
        ticket.setTicketNo("WX20260101001");

        publisher.notifyTransition(ticket, TicketAction.ACCEPT, null, null);

        verifyNoInteractions(notificationService);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).append(eq(1L), eq("TICKET"), eq(99L),
                eq(NotificationCommand.EVENT_TYPE), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(NotificationCommand.class);
        NotificationCommand command = (NotificationCommand) payload.getValue();
        assertThat(command.tenantId()).isEqualTo(1L);
        assertThat(command.receiverId()).isEqualTo(2L);
        assertThat(command.type()).isEqualTo("TICKET_ACCEPTED");
        assertThat(command.title()).isEqualTo("维修工已接单");
        assertThat(command.content()).contains("WX20260101001");
        assertThat(command.ticketId()).isEqualTo(99L);
    }
}
