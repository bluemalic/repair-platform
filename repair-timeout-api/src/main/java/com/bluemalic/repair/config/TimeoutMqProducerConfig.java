package com.bluemalic.repair.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 超时的 MQ **生产者**装配（阶段 3.4 从 {@code TimeoutMqConfig} 拆出来）。
 *
 * <p>生产者跟着"登记"走：工单命令路径要发定时消息，所以它必须和 {@code TimeoutRegistrarImpl} 在同一个模块。
 * 消费者留在 {@code repair-timeout-service}（只有它该消费）。
 *
 * <p>两个条件都要满足：{@code repair.timeout.delivery=mq} 且 {@code repair.rocketmq.enabled=true}。
 * 只配一个是部署错误，会在启动时报"缺少 timeoutProducer Bean"——比运行到第一条超时才炸好查。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "repair.timeout", name = "delivery", havingValue = "mq")
public class TimeoutMqProducerConfig {

    private final TimeoutProperties timeoutProperties;
    private final RocketMqProperties rocketMqProperties;

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "repair.rocketmq", name = "enabled", havingValue = "true")
    public DefaultMQProducer timeoutProducer() throws MQClientException {
        DefaultMQProducer producer = new DefaultMQProducer(timeoutProperties.getProducerGroup());
        producer.setNamesrvAddr(rocketMqProperties.getNameServer());
        producer.setVipChannelEnabled(false);
        // 定时消息的首次发送要等 broker 的 timer 路由，3s 默认值在冷启动时偏紧
        producer.setSendMsgTimeout(10_000);
        producer.start();
        log.info("超时 RocketMQ producer 已启动 group={} namesrv={}",
                timeoutProperties.getProducerGroup(), rocketMqProperties.getNameServer());
        return producer;
    }
}
