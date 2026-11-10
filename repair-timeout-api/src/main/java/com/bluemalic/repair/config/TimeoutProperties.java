package com.bluemalic.repair.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 超时投递方式配置（阶段 2 · ADR-024）。
 *
 * <ul>
 *   <li>{@code repair.timeout.delivery=zset}（默认）：单体每秒轮询 Redis ZSet（ADR-001），
 *       生产当前口径，行为不变；</li>
 *   <li>{@code repair.timeout.delivery=mq}：登记点直接发 RocketMQ 定时消息
 *       （{@code setDeliverTimeMs}），由 timeout-service 消费到期消息执行三个动作；
 *       ZSet 秒级消费关闭，兜底扫库仍留在单体（ADR-001 防线）。</li>
 * </ul>
 *
 * <p>两个进程的分工由环境变量决定：单体 {@code delivery=mq} + {@code backstop-enabled=true} +
 * {@code consumer-enabled=false}；timeout 进程 {@code delivery=mq} + {@code consumer-enabled=true}
 * + {@code backstop-enabled=false}。
 */
@Getter
@Component
public class TimeoutProperties {

    public static final String DELIVERY_ZSET = "zset";
    public static final String DELIVERY_MQ = "mq";

    private final String delivery;
    private final boolean consumerEnabled;
    private final boolean backstopEnabled;
    private final String timeoutTopic;
    private final String producerGroup;
    private final String consumerGroup;

    public TimeoutProperties(
            @Value("${repair.timeout.delivery:zset}") String delivery,
            @Value("${repair.timeout.consumer-enabled:false}") boolean consumerEnabled,
            @Value("${repair.timeout.backstop-enabled:true}") boolean backstopEnabled,
            @Value("${repair.timeout.timeout-topic:repair-ticket-timeouts}") String timeoutTopic,
            @Value("${repair.timeout.producer-group:repair-timeout-producer}") String producerGroup,
            @Value("${repair.timeout.consumer-group:repair-timeout-service}") String consumerGroup) {
        String normalized = delivery == null ? DELIVERY_ZSET : delivery.trim().toLowerCase(Locale.ROOT);
        if (!DELIVERY_ZSET.equals(normalized) && !DELIVERY_MQ.equals(normalized)) {
            // 配错时直接启动失败，不要静默落回 zset——两种模式的故障表现完全不同，猜错更难查
            throw new IllegalArgumentException(
                    "repair.timeout.delivery 只能是 zset 或 mq，当前值=" + delivery);
        }
        this.delivery = normalized;
        this.consumerEnabled = consumerEnabled;
        this.backstopEnabled = backstopEnabled;
        this.timeoutTopic = timeoutTopic;
        this.producerGroup = producerGroup;
        this.consumerGroup = consumerGroup;
    }

    public boolean isZsetDelivery() {
        return DELIVERY_ZSET.equals(delivery);
    }

    public boolean isMqDelivery() {
        return DELIVERY_MQ.equals(delivery);
    }
}
