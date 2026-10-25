package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.IntegrationTest;
import com.bluemalic.repair.common.NotificationCommand;
import com.bluemalic.repair.entity.ConsumedEvent;
import com.bluemalic.repair.entity.Notification;
import com.bluemalic.repair.mapper.ConsumedEventMapper;
import com.bluemalic.repair.mapper.NotificationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class NotificationConsumerServiceTest {

    @Autowired
    private NotificationConsumerService consumerService;

    @Autowired
    private ConsumedEventMapper consumedEventMapper;

    @Autowired
    private NotificationMapper notificationMapper;

    @Test
    void sameEventIdIsConsumedOnlyOnce() {
        long ticketId = System.nanoTime();
        String eventId = "idem-" + ticketId;
        NotificationCommand command = new NotificationCommand(
                1L, 2L, "TICKET_ACCEPTED", "维修工已接单", "工单 WX1 已被接单", ticketId);

        consumerService.consume(1L, "test-consumer-group", eventId, command);
        consumerService.consume(1L, "test-consumer-group", eventId, command);

        assertThat(notificationMapper.selectCount(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getTicketId, ticketId)))
                .as("同一 eventId 只能写一条站内信").isEqualTo(1);
        assertThat(consumedEventMapper.selectCount(Wrappers.<ConsumedEvent>lambdaQuery()
                .eq(ConsumedEvent::getConsumerGroup, "test-consumer-group")
                .eq(ConsumedEvent::getEventId, eventId)))
                .as("幂等记录只能有一条").isEqualTo(1);
    }
}
