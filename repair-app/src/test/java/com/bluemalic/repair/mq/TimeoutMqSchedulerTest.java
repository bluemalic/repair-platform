package com.bluemalic.repair.mq;

import com.bluemalic.repair.config.TimeoutProperties;
import com.bluemalic.repair.service.TimeoutCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * delivery=mq 的定时消息登记：消息头/载荷/到期时间必须对得上，
 * 发送失败必须向上抛（让业务事务回滚，而不是静默丢掉超时）。
 */
class TimeoutMqSchedulerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsTimerMessageWithDeadlineAndTenant() throws Exception {
        DefaultMQProducer producer = mock(DefaultMQProducer.class);
        when(producer.send(any(Message.class))).thenReturn(mock(SendResult.class));
        TimeoutProperties properties = new TimeoutProperties(
                "mq", true, false, "repair-ticket-timeouts", "repair-timeout-producer", "repair-timeout-service");
        TimeoutMqScheduler scheduler = new TimeoutMqScheduler(producer, properties, objectMapper);

        Instant deadline = Instant.now().plusSeconds(3600);
        scheduler.schedule(TimeoutCommand.NODE_ACCEPT, 7L, 3L, deadline);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());
        Message message = captor.getValue();

        assertThat(message.getTopic()).isEqualTo("repair-ticket-timeouts");
        assertThat(message.getTags()).isEqualTo(TimeoutCommand.NODE_ACCEPT);
        assertThat(message.getKeys()).isEqualTo("3|7");
        assertThat(message.getUserProperty("tenantId")).isEqualTo("3");
        // timer 消息：到期时刻就是 deadline（允许毫秒级抖动）
        assertThat(Math.abs(message.getDeliverTimeMs() - deadline.toEpochMilli())).isLessThan(2000);
        TimeoutCommand command = objectMapper.readValue(message.getBody(), TimeoutCommand.class);
        assertThat(command).isEqualTo(new TimeoutCommand(3L, 7L, TimeoutCommand.NODE_ACCEPT));
    }

    @Test
    void sendFailurePropagatesSoBusinessTransactionRollsBack() throws Exception {
        DefaultMQProducer producer = mock(DefaultMQProducer.class);
        when(producer.send(any(Message.class))).thenThrow(new RuntimeException("mq down"));
        TimeoutProperties properties = new TimeoutProperties(
                "mq", true, false, "t", "pg", "cg");
        TimeoutMqScheduler scheduler = new TimeoutMqScheduler(producer, properties, objectMapper);

        assertThatThrownBy(() -> scheduler.schedule(TimeoutCommand.NODE_EVAL, 7L, 3L, Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超时任务投递失败");
    }
}
