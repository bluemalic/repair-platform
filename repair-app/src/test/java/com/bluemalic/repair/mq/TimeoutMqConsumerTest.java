package com.bluemalic.repair.mq;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.service.DueTicket;
import com.bluemalic.repair.service.TimeoutCommand;
import com.bluemalic.repair.service.impl.TicketTimeoutHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 到期消息的消费语义：三个节点路由到对应动作；坏消息消费掉；业务异常跳过；其余异常重投。
 * 幂等由处理器保证（状态 CAS + ticket_log 判重）——消费端不记忆"见过"，重复投递会再调一次处理器，
 * 由处理器自己跳过；这条分工在这里钉住。
 */
class TimeoutMqConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TicketTimeoutHandler handler = mock(TicketTimeoutHandler.class);
    private final TimeoutMqConsumer consumer = new TimeoutMqConsumer(handler, objectMapper);

    @Test
    void routesEachNodeToItsAction() throws Exception {
        assertThat(consume(TimeoutCommand.NODE_ACCEPT)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(handler).remindAcceptTimeout(new DueTicket(3L, 7L));

        assertThat(consume(TimeoutCommand.NODE_PROCESS)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(handler).escalateProcessTimeout(new DueTicket(3L, 7L));

        assertThat(consume(TimeoutCommand.NODE_EVAL)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(handler).autoClose(new DueTicket(3L, 7L));
    }

    @Test
    void duplicateDeliveryCallsHandlerAgainAndHandlerDecidesIdempotency() throws Exception {
        MessageExt message = messageOf(TimeoutCommand.NODE_ACCEPT);

        assertThat(consumer.consume(List.of(message))).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        assertThat(consumer.consume(List.of(message))).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);

        // 消费端不会因为"见过"就跳过——幂等在处理器（状态 + ticket_log）里，两进程/重投都走同一条路
        verify(handler, times(2)).remindAcceptTimeout(new DueTicket(3L, 7L));
    }

    @Test
    void badJsonIsConsumedWithoutRetry() {
        MessageExt message = new MessageExt();
        message.setBody("{not-json".getBytes(StandardCharsets.UTF_8));

        assertThat(consumer.consume(List.of(message))).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verifyNoInteractions(handler);
    }

    @Test
    void unknownNodeIsConsumedWithoutRetry() throws Exception {
        MessageExt message = new MessageExt();
        message.setBody(objectMapper.writeValueAsBytes(new TimeoutCommand(3L, 7L, "NOPE")));

        assertThat(consumer.consume(List.of(message))).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verifyNoInteractions(handler);
    }

    @Test
    void businessExceptionMeansAlreadyTransitionedAndIsConsumed() throws Exception {
        doThrow(new BizException(ErrorCode.PARAM_INVALID, "状态已流转"))
                .when(handler).remindAcceptTimeout(any(DueTicket.class));

        assertThat(consume(TimeoutCommand.NODE_ACCEPT))
                .isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
    }

    @Test
    void otherExceptionRequestsRedelivery() throws Exception {
        doThrow(new IllegalStateException("db down"))
                .when(handler).remindAcceptTimeout(any(DueTicket.class));

        assertThat(consume(TimeoutCommand.NODE_ACCEPT))
                .isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
    }

    private ConsumeConcurrentlyStatus consume(String node) throws Exception {
        return consumer.consume(List.of(messageOf(node)));
    }

    private MessageExt messageOf(String node) throws Exception {
        MessageExt message = new MessageExt();
        message.setTopic("repair-ticket-timeouts");
        message.setTags(node);
        message.setKeys("3|7");
        message.putUserProperty("tenantId", "3");
        message.setBody(objectMapper.writeValueAsBytes(new TimeoutCommand(3L, 7L, node)));
        return message;
    }
}
