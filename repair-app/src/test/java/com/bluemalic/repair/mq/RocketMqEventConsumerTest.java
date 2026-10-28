package com.bluemalic.repair.mq;

import com.bluemalic.repair.common.NotificationCommand;
import com.bluemalic.repair.config.RocketMqProperties;
import com.bluemalic.repair.service.impl.NotificationConsumerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RocketMqEventConsumerTest {

    @Test
    void notificationEventIsParsedAndDelegated() throws Exception {
        NotificationConsumerService consumerService = mock(NotificationConsumerService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RocketMqProperties properties = new RocketMqProperties(
                true, "127.0.0.1:9876", "topic", "producer", "repair-consumer", true);
        RocketMqEventConsumer consumer = new RocketMqEventConsumer(consumerService, objectMapper, properties);

        NotificationCommand command = new NotificationCommand(
                1L, 2L, "TICKET_ACCEPTED", "维修工已接单", "工单 WX1 已被接单", 99L);
        MessageExt message = new MessageExt();
        message.setTopic("repair-ticket-events");
        message.setTags(NotificationCommand.EVENT_TYPE);
        message.setKeys("99");
        message.putUserProperty("eventId", "e-1");
        message.setBody(objectMapper.writeValueAsBytes(command));

        ConsumeConcurrentlyStatus status = consumer.consume(List.of(message));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        ArgumentCaptor<NotificationCommand> captor = ArgumentCaptor.forClass(NotificationCommand.class);
        verify(consumerService).consume(eq(1L), eq("repair-consumer"), eq("e-1"), captor.capture());
        assertThat(captor.getValue().receiverId()).isEqualTo(2L);
        assertThat(captor.getValue().type()).isEqualTo("TICKET_ACCEPTED");
    }
}
