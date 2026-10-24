package com.bluemalic.repair.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 连接与主题配置（阶段 2 · ADR-023）。
 *
 * <p>默认关闭：本地开发与测试不依赖 MQ；生产或本地 smoke test 用
 * {@code REPAIR_ROCKETMQ_ENABLED=true} 打开。
 */
@Getter
@Component
public class RocketMqProperties {

    private final boolean enabled;
    private final String nameServer;
    private final String topic;
    private final String producerGroup;
    private final String consumerGroup;

    public RocketMqProperties(
            @Value("${repair.rocketmq.enabled:false}") boolean enabled,
            @Value("${repair.rocketmq.name-server:127.0.0.1:9876}") String nameServer,
            @Value("${repair.rocketmq.topic:repair-ticket-events}") String topic,
            @Value("${repair.rocketmq.producer-group:repair-producer}") String producerGroup,
            @Value("${repair.rocketmq.consumer-group:repair-notification-consumer}") String consumerGroup) {
        this.enabled = enabled;
        this.nameServer = nameServer;
        this.topic = topic;
        this.producerGroup = producerGroup;
        this.consumerGroup = consumerGroup;
    }
}
