package com.bluemalic.repair.mq;

import com.bluemalic.repair.common.TicketStatusChangedEvent;
import com.bluemalic.repair.config.RocketMqProperties;
import com.bluemalic.repair.entity.OutboxEvent;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RocketMqOutboxPublisherTest {

    @Test
    void publishesPayloadWithTicketKeyAndEventId() throws Exception {
        DefaultMQProducer producer = mock(DefaultMQProducer.class);
        when(producer.send(any(Message.class))).thenReturn(new SendResult());
        RocketMqProperties properties = new RocketMqProperties(
                true, "127.0.0.1:9876", "repair-ticket-events", "repair-producer", "repair-consumer", true);
        RocketMqOutboxPublisher publisher = new RocketMqOutboxPublisher(producer, properties);

        OutboxEvent event = new OutboxEvent();
        event.setEventId("e-1");
        event.setTenantId(1L);
        event.setAggregateId(42L);
        event.setEventType(TicketStatusChangedEvent.EVENT_TYPE);
        event.setPayload("{\"action\":\"DISPATCH\"}");

        publisher.handle(event);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());
        Message message = captor.getValue();
        assertThat(message.getTopic()).isEqualTo("repair-ticket-events");
        assertThat(message.getTags()).isEqualTo(TicketStatusChangedEvent.EVENT_TYPE);
        assertThat(message.getKeys()).isEqualTo("42");
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"action\":\"DISPATCH\"}");
        assertThat(message.getUserProperty("eventId")).isEqualTo("e-1");
        assertThat(message.getUserProperty("tenantId")).isEqualTo("1");
    }
}
