package com.bluemalic.repair.mq;

import com.bluemalic.repair.config.TimeoutProperties;
import com.bluemalic.repair.service.DueTicket;
import com.bluemalic.repair.service.TimeoutCommand;
import com.bluemalic.repair.service.impl.TicketTimeoutHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 真实 RocketMQ 的定时消息 smoke（默认不跑，CI 不依赖 MQ）。
 *
 * <p>验证链路：{@link TimeoutMqScheduler} 发 {@code setDeliverTimeMs} 定时消息 →
 * broker 延迟 ~5s → {@link TimeoutMqConsumer} 收到并调 {@link TicketTimeoutHandler}。
 * 这补的是单元测试覆盖不到的一环：**broker 的 timer 能力真的开着**（timerWheelEnable）。
 *
 * <pre>
 * # brokerIP1 临时改成 127.0.0.1（宿主机跑），再执行：
 * ./mvnw.cmd -B -Djacoco.skip=true -Drun.rocketmq.smoke=true -Dtest=TimeoutMqBrokerSmoke test
 * </pre>
 *
 * <p>类名故意不以 Test 结尾，避免被 Surefire 默认扫描进全量测试。
 */
@EnabledIfSystemProperty(named = "run.rocketmq.smoke", matches = "true")
class TimeoutMqBrokerSmoke {

    @Test
    void timerMessageIsDeliveredAfterDelay() throws Exception {
        String namesrv = System.getProperty("rocketmq.smoke.namesrv", "127.0.0.1:9876");
        String topic = "repair-ticket-timeouts";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long delayMs = 5_000;

        TicketTimeoutHandler handler = mock(TicketTimeoutHandler.class);
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(handler).remindAcceptTimeout(any(DueTicket.class));

        ObjectMapper objectMapper = new ObjectMapper();
        DefaultMQProducer producer = new DefaultMQProducer("smoke-timeout-producer-" + suffix);
        producer.setNamesrvAddr(namesrv);
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(10_000);
        producer.start();

        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("smoke-timeout-consumer-" + suffix);
        consumer.setNamesrvAddr(namesrv);
        consumer.setVipChannelEnabled(false);
        TimeoutMqConsumer delegate = new TimeoutMqConsumer(handler, objectMapper);
        consumer.registerMessageListener(
                (MessageListenerConcurrently) (messages, context) -> delegate.consume(messages));
        try {
            // 先发一条普通消息把 topic 建出来，否则 consumer 订阅到不存在的 topic 拿不到路由
            producer.send(new Message(topic, "WARMUP", "warmup".getBytes(StandardCharsets.UTF_8)));
            consumer.subscribe(topic, "*");
            consumer.start();
            Thread.sleep(1_500);

            TimeoutProperties properties = new TimeoutProperties(
                    "mq", true, false, topic, "smoke-timeout-producer", "smoke-timeout-consumer");
            TimeoutMqScheduler scheduler = new TimeoutMqScheduler(producer, properties, objectMapper);

            long start = System.currentTimeMillis();
            scheduler.schedule(TimeoutCommand.NODE_ACCEPT, 7L, 3L, Instant.now().plusMillis(delayMs));

            boolean received = latch.await(40, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;
            System.out.printf("定时消息到达=%s 耗时=%dms%n", received, elapsed);

            assertThat(received).as("定时消息应在 ~5s 后投递（broker timer 生效）").isTrue();
            assertThat(elapsed).as("不能是立即投递").isGreaterThanOrEqualTo(4_000);
            verify(handler).remindAcceptTimeout(new DueTicket(3L, 7L));
        } finally {
            consumer.shutdown();
            producer.shutdown();
        }
    }
}
