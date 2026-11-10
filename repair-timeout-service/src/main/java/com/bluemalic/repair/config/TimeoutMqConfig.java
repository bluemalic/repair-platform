package com.bluemalic.repair.config;

import com.bluemalic.repair.mq.TimeoutMqConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 超时链路的 MQ **消费者**装配（阶段 2 · ADR-024；阶段 3.4 把生产者拆到
 * {@code repair-timeout-api} 的 {@link TimeoutMqProducerConfig}）。
 *
 * <p>消费者单独用 {@code consumer-enabled} 条件：单体在 mq 模式下也要发超时消息
 * （登记点在工单命令路径），但**只有 timeout-service 该消费**，所以单体设
 * {@code REPAIR_TIMEOUT_CONSUMER_ENABLED=false}。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "repair.timeout", name = "delivery", havingValue = "mq")
@ConditionalOnProperty(prefix = "repair.timeout", name = "consumer-enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "repair.rocketmq", name = "enabled", havingValue = "true")
public class TimeoutMqConfig {

    private final TimeoutProperties timeoutProperties;
    private final RocketMqProperties rocketMqProperties;

    @Bean(destroyMethod = "shutdown")
    public DefaultMQPushConsumer timeoutConsumer(TimeoutMqConsumer consumer) throws MQClientException {
        DefaultMQPushConsumer pushConsumer =
                new DefaultMQPushConsumer(timeoutProperties.getConsumerGroup());
        pushConsumer.setNamesrvAddr(rocketMqProperties.getNameServer());
        pushConsumer.setVipChannelEnabled(false);
        pushConsumer.subscribe(timeoutProperties.getTimeoutTopic(), "*");
        pushConsumer.registerMessageListener(
                (MessageListenerConcurrently) (messages, context) -> consumer.consume(messages));
        pushConsumer.start();
        log.info("超时 RocketMQ consumer 已启动 group={} topic={} namesrv={}",
                timeoutProperties.getConsumerGroup(), timeoutProperties.getTimeoutTopic(),
                rocketMqProperties.getNameServer());
        return pushConsumer;
    }
}
