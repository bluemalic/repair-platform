package com.bluemalic.repair.mq;

import com.bluemalic.repair.common.NotificationCommand;
import com.bluemalic.repair.common.TicketStatusChangedEvent;
import com.bluemalic.repair.config.RocketMqProperties;
import com.bluemalic.repair.entity.OutboxEvent;
import com.bluemalic.repair.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 真实 RocketMQ 的手动 smoke test（默认不跑，CI 不依赖 MQ）。
 *
 * <pre>
 * docker compose --profile rocketmq up -d rocketmq-namesrv rocketmq-broker
 * # 宿主机跑时，broker.conf 的 brokerIP1 需临时改成 127.0.0.1
 * ./mvnw.cmd -B -Djacoco.skip=true -Drun.rocketmq.smoke=true \
 *   -Dtest=RocketMqBrokerSmoke test
 * </pre>
 *
 * <p>类名故意不以 Test 结尾，避免被 Surefire 默认扫描进全量测试。
 */
@EnabledIfSystemProperty(named = "run.rocketmq.smoke", matches = "true")
class RocketMqBrokerSmoke {

    @Test
    void publishesAndConsumesRealMessage() throws Exception {
        String namesrv = System.getProperty("rocketmq.smoke.namesrv", "127.0.0.1:9876");
        String topic = "repair-ticket-events";
        String eventId = "smoke-" + UUID.randomUUID();
        String payload = "{\"action\":\"SMOKE\"}";

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<MessageExt> received = new AtomicReference<>();

        DefaultMQProducer producer = new DefaultMQProducer("repair-smoke-producer");
        producer.setNamesrvAddr(namesrv);
        producer.setVipChannelEnabled(false);
        // 连续跑两个用例时，第二个 producer group 的首次路由/心跳可能来不及在默认 3s 内完成
        producer.setSendMsgTimeout(10_000);
        producer.setRetryTimesWhenSendFailed(2);
        producer.start();
        try {
            // 先发一条 warmup，确保 topic 已创建；否则 consumer 订阅一个不存在的 topic 可能拿不到路由
            producer.send(new Message(topic, "WARMUP", "warmup".getBytes(StandardCharsets.UTF_8)));

            DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("repair-smoke-consumer");
            try {
                consumer.setNamesrvAddr(namesrv);
                consumer.setVipChannelEnabled(false);
                consumer.subscribe(topic, "*");
                RocketMqEventConsumer appConsumer = new RocketMqEventConsumer(
                        mock(NotificationService.class), new ObjectMapper());
                consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                    appConsumer.consume(messages);
                    for (MessageExt message : messages) {
                        if (eventId.equals(message.getUserProperty("eventId"))) {
                            received.compareAndSet(null, message);
                            latch.countDown();
                        }
                    }
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                });
                consumer.start();
                // 等 consumer 拿到路由
                Thread.sleep(1500);

                RocketMqProperties properties = new RocketMqProperties(
                        true, namesrv, topic, "repair-smoke-producer", "repair-smoke-consumer");
                RocketMqOutboxPublisher publisher = new RocketMqOutboxPublisher(producer, properties);

                OutboxEvent event = new OutboxEvent();
                event.setEventId(eventId);
                event.setTenantId(1L);
                event.setAggregateId(42L);
                event.setEventType(TicketStatusChangedEvent.EVENT_TYPE);
                event.setPayload(payload);
                publisher.handle(event);

                assertThat(latch.await(10, TimeUnit.SECONDS))
                        .as("10 秒内应从真实 broker 收到消息").isTrue();
                MessageExt message = received.get();
                assertThat(message).isNotNull();
                assertThat(message.getTopic()).isEqualTo(topic);
                assertThat(message.getTags()).isEqualTo(TicketStatusChangedEvent.EVENT_TYPE);
                assertThat(message.getKeys()).isEqualTo("42");
                assertThat(message.getUserProperty("eventId")).isEqualTo(eventId);
                assertThat(message.getUserProperty("tenantId")).isEqualTo("1");
                assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(payload);
            } finally {
                consumer.shutdown();
            }
        } finally {
            producer.shutdown();
        }
    }

    @Test
    void publishesAndConsumesNotificationCommand() throws Exception {
        String namesrv = System.getProperty("rocketmq.smoke.namesrv", "127.0.0.1:9876");
        String topic = "repair-ticket-events";
        ObjectMapper objectMapper = new ObjectMapper();
        NotificationCommand command = new NotificationCommand(
                1L, 2L, "TICKET_ACCEPTED", "维修工已接单", "工单 WX1 已被接单", 42L);
        String payload = objectMapper.writeValueAsString(command);
        CountDownLatch latch = new CountDownLatch(1);

        NotificationService notificationService = mock(NotificationService.class);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(notificationService).send(any(), any(), any(), any(), any(), any());

        DefaultMQProducer producer = new DefaultMQProducer("repair-smoke-producer-notify");
        producer.setNamesrvAddr(namesrv);
        producer.setVipChannelEnabled(false);
        // 连续跑两个用例时，第二个 producer group 的首次路由/心跳可能来不及在默认 3s 内完成
        producer.setSendMsgTimeout(10_000);
        producer.setRetryTimesWhenSendFailed(2);
        producer.start();
        try {
            producer.send(new Message(topic, "WARMUP", "warmup".getBytes(StandardCharsets.UTF_8)));

            DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("repair-smoke-consumer-notify");
            try {
                consumer.setNamesrvAddr(namesrv);
                consumer.setVipChannelEnabled(false);
                consumer.subscribe(topic, "*");
                RocketMqEventConsumer appConsumer = new RocketMqEventConsumer(notificationService, objectMapper);
                consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) ->
                        appConsumer.consume(messages));
                consumer.start();
                Thread.sleep(1500);

                RocketMqProperties properties = new RocketMqProperties(true, namesrv, topic,
                        "repair-smoke-producer-notify", "repair-smoke-consumer-notify");
                RocketMqOutboxPublisher publisher = new RocketMqOutboxPublisher(producer, properties);

                OutboxEvent event = new OutboxEvent();
                event.setEventId("smoke-notify-" + UUID.randomUUID());
                event.setTenantId(1L);
                event.setAggregateId(42L);
                event.setEventType(NotificationCommand.EVENT_TYPE);
                event.setPayload(payload);
                publisher.handle(event);

                assertThat(latch.await(10, TimeUnit.SECONDS))
                        .as("10 秒内消费者应调用 NotificationService.send").isTrue();
                verify(notificationService).send(1L, 2L, "TICKET_ACCEPTED",
                        "维修工已接单", "工单 WX1 已被接单", 42L);
            } finally {
                consumer.shutdown();
            }
        } finally {
            producer.shutdown();
        }
    }
}
