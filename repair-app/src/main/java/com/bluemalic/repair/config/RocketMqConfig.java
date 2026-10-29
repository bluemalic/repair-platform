package com.bluemalic.repair.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * RocketMQ 生产者装配（阶段 2 · ADR-023）。
 *
 * <p>只在 {@code repair.rocketmq.enabled=true} 时创建；本地与测试默认关闭，不依赖 MQ。
 * 关闭 VIP 通道，只走 10911，少暴露一个端口。消费者在 {@link RocketMqConsumerConfig}。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "repair.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqConfig {

    private final RocketMqProperties properties;

    /**
     * 单体的 Outbox 生产者。
     *
     * <p>标 {@code @Primary}：delivery=mq 时容器里还会有 timeout 模块的 {@code timeoutProducer}，
     * 而 {@code RocketMqOutboxPublisher} 是按类型注入的——没有 primary 会在 mq 模式下歧义。
     * timeout 侧的生产者用 {@code @Qualifier("timeoutProducer")} 精确取。
     */
    @Bean(destroyMethod = "shutdown")
    @Primary
    public DefaultMQProducer rocketMqProducer() throws MQClientException {
        DefaultMQProducer producer = new DefaultMQProducer(properties.getProducerGroup());
        producer.setNamesrvAddr(properties.getNameServer());
        producer.setVipChannelEnabled(false);
        producer.start();
        log.info("RocketMQ producer 已启动 group={} namesrv={}",
                properties.getProducerGroup(), properties.getNameServer());
        return producer;
    }

}
