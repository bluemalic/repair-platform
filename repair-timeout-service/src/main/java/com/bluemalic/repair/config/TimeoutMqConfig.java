package com.bluemalic.repair.config;

import com.bluemalic.repair.mq.TimeoutMqConsumer;
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
 * 超时链路的 MQ 装配（阶段 2 · ADR-024）。只在 {@code repair.timeout.delivery=mq} 时生效。
 *
 * <p>生产者/消费者都依赖 {@code repair.rocketmq.enabled=true} 才创建：配置了 mq 模式却没开 MQ
 * 属于部署错误，会以"缺少 timeoutProducer Bean"在启动时直接失败——比运行到第一条超时才炸好查。
 *
 * <p>消费者单独用嵌套配置加 {@code consumer-enabled} 条件：单体在 mq 模式下也要发超时消息
 * （登记点在单体），但**只有 timeout-service 该消费**，所以单体设
 * {@code REPAIR_TIMEOUT_CONSUMER_ENABLED=false}。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "repair.timeout", name = "delivery", havingValue = "mq")
public class TimeoutMqConfig {

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

    @Configuration
    @RequiredArgsConstructor
    @ConditionalOnProperty(prefix = "repair.timeout", name = "consumer-enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "repair.rocketmq", name = "enabled", havingValue = "true")
    static class ConsumerConfig {

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
}
