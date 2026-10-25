package com.bluemalic.repair.mq;

import com.bluemalic.repair.common.NotificationCommand;
import com.bluemalic.repair.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RocketMqEventConsumerTest {

    @Test
    void notificationEventIsParsedAndSent() throws Exception {
        NotificationService notificationService = mock(NotificationService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RocketMqEventConsumer consumer = new RocketMqEventConsumer(notificationService, objectMapper);

        NotificationCommand command = new NotificationCommand(
                1L, 2L, "TICKET_ACCEPTED", "维修工已接单", "工单 WX1 已被接单", 99L);
        MessageExt message = new MessageExt();
        message.setTopic("repair-ticket-events");
        message.setTags(NotificationCommand.EVENT_TYPE);
        message.setKeys("99");
        message.setBody(objectMapper.writeValueAsBytes(command));

        ConsumeConcurrentlyStatus status = consumer.consume(List.of(message));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(notificationService).send(1L, 2L, "TICKET_ACCEPTED",
                "维修工已接单", "工单 WX1 已被接单", 99L);
    }
}
