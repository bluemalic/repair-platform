package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.MetricsRecorder;
import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.common.TicketStatusChangedEvent;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.OutboxService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TicketTransitionSupportTest {

    @Test
    void conditionalUpdateAppendsOutboxEventAfterSuccessfulUpdate() {
        TicketMapper ticketMapper = mock(TicketMapper.class);
        when(ticketMapper.update(any(), any())).thenReturn(1);
        OutboxService outboxService = mock(OutboxService.class);
        TicketTransitionSupport support = new TicketTransitionSupport(
                ticketMapper, mock(TicketLogMapper.class), mock(MetricsRecorder.class), outboxService);

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
}
