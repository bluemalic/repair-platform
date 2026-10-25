package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.NotificationCommand;
import com.bluemalic.repair.config.RocketMqProperties;
import com.bluemalic.repair.entity.OutboxEvent;
import com.bluemalic.repair.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LocalNotificationEventHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void localHandlerSendsWhenRocketMqDisabled() throws Exception {
        NotificationService notificationService = mock(NotificationService.class);
        RocketMqProperties properties = new RocketMqProperties(
                false, "127.0.0.1:9876", "topic", "producer", "consumer", true);
        LocalNotificationEventHandler handler = new LocalNotificationEventHandler(
                notificationService, objectMapper, properties);

        NotificationCommand command = new NotificationCommand(
                1L, 2L, "TICKET_ACCEPTED", "维修工已接单", "工单 WX1 已被接单", 99L);
        OutboxEvent event = new OutboxEvent();
        event.setEventId("e-1");
        event.setPayload(objectMapper.writeValueAsString(command));

        assertThat(handler.supports(NotificationCommand.EVENT_TYPE)).isTrue();
        handler.handle(event);

        verify(notificationService).sendOrThrow(1L, 2L, "TICKET_ACCEPTED",
                "维修工已接单", "工单 WX1 已被接单", 99L);
    }

    @Test
    void localHandlerYieldsToRocketMqWhenEnabled() {
        RocketMqProperties properties = new RocketMqProperties(
                true, "127.0.0.1:9876", "topic", "producer", "consumer", true);
        LocalNotificationEventHandler handler = new LocalNotificationEventHandler(
                mock(NotificationService.class), objectMapper, properties);

        assertThat(handler.supports(NotificationCommand.EVENT_TYPE)).isFalse();
    }
}
