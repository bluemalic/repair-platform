package com.bluemalic.repair.config;

import com.bluemalic.repair.mq.RocketMqEventConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 只装配 RocketMQ 消费者（阶段 2 · ADR-023）。
 *
 * <p>独立出来是为了让 `notification-service` 只导入 consumer 配置，不用把 producer 也拉起来；
 * 单体应用同时扫描本配置与 {@link RocketMqConfig}，行为不变。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "repair.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqConsumerConfig {

    private final RocketMqProperties properties;

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "repair.rocketmq", name = "consumer-enabled",
            havingValue = "true", matchIfMissing = true)
    public DefaultMQPushConsumer rocketMqConsumer(RocketMqEventConsumer consumer) throws MQClientException {
        DefaultMQPushConsumer pushConsumer = new DefaultMQPushConsumer(properties.getConsumerGroup());
        pushConsumer.setNamesrvAddr(properties.getNameServer());
        pushConsumer.setVipChannelEnabled(false);
        pushConsumer.subscribe(properties.getTopic(), "*");
        pushConsumer.registerMessageListener(
                (MessageListenerConcurrently) (messages, context) -> consumer.consume(messages));
        pushConsumer.start();
        log.info("RocketMQ consumer 已启动 group={} topic={} namesrv={}",
                properties.getConsumerGroup(), properties.getTopic(), properties.getNameServer());
        return pushConsumer;
    }
}
