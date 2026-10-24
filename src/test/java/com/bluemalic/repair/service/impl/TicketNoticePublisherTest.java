package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.service.NotificationService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TicketNoticePublisherTest {

    @Test
    void transferNoticeWithNoWorkerIsSkippedInsteadOfThrowing() {
        NotificationService notificationService = mock(NotificationService.class);
        TicketNoticePublisher publisher = new TicketNoticePublisher(notificationService);

        Ticket ticket = new Ticket();
        ticket.setTenantId(1L);
        ticket.setStudentId(2L);
        ticket.setTicketNo("WX20260101001");
        // workerId 为 null：转派 / 管理员驳回清空主责后可能出现。
        // 修复前这里对 Long 拆箱会 NPE，接口报 500；修复后应静默跳过。
        publisher.notifyTransition(ticket, TicketAction.TRANSFER, null, "已转给新师傅");

        verifyNoInteractions(notificationService);
    }
}
