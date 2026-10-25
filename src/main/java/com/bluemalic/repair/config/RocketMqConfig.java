package com.bluemalic.repair.config;

import com.bluemalic.repair.mq.RocketMqEventConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 生产者/消费者装配（阶段 2 · ADR-023）。
 *
 * <p>只在 {@code repair.rocketmq.enabled=true} 时创建；本地与测试默认关闭，不依赖 MQ。
 * 关闭 VIP 通道，只走 10911，少暴露一个端口。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "repair.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqConfig {

    private final RocketMqProperties properties;

    @Bean(destroyMethod = "shutdown")
    public DefaultMQProducer rocketMqProducer() throws MQClientException {
        DefaultMQProducer producer = new DefaultMQProducer(properties.getProducerGroup());
        producer.setNamesrvAddr(properties.getNameServer());
        producer.setVipChannelEnabled(false);
        producer.start();
        log.info("RocketMQ producer 已启动 group={} namesrv={}",
                properties.getProducerGroup(), properties.getNameServer());
        return producer;
    }

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
