package com.bluemalic.repair.mq;

import com.bluemalic.repair.config.TimeoutProperties;
import com.bluemalic.repair.service.TimeoutCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 超时登记点的 MQ 实现（阶段 2 · ADR-024）：把"到期时间"直接投成 RocketMQ 定时消息。
 *
 * <p><b>为什么在登记点发，而不是让 timeout-service 消费 TICKET_* 事件再算截止时间</b>：
 * 截止时间需要 {@code dispatch_time} / {@code finish_time}，而状态变更事件的载荷只有
 * 状态/动作/操作人。要让消费端算准，只能改事件协议（旧消息反序列化风险）或让消费端跨服务读工单表
 * （ADR-024 明确不做）。登记点（{@code TimeoutService.register*}）本来就知道准确基准时间，
 * 直接发定时消息两者都不需要。{@code TimeoutServiceImpl.cancel} 在 MQ 模式下是"逻辑取消"——
 * 定时消息无法撤回，到期执行前由状态 CAS + ticket_log 判重兜底。
 *
 * <p>发送失败抛 {@link IllegalStateException}：与 zset 写 Redis 失败同一语义，让业务事务回滚，
 * 不静默丢掉超时登记。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "repair.timeout", name = "delivery", havingValue = "mq")
public class TimeoutMqScheduler {

    private final DefaultMQProducer timeoutProducer;
    private final TimeoutProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 用显式构造器而不是 Lombok：单体在 mq 模式下同时有 Outbox 的生产者（rocketMqProducer）
     * 与本模块的超时生产者（timeoutProducer），按类型注入会歧义，必须按 bean 名限定。
     */
    public TimeoutMqScheduler(@Qualifier("timeoutProducer") DefaultMQProducer timeoutProducer,
                              TimeoutProperties properties,
                              ObjectMapper objectMapper) {
        this.timeoutProducer = timeoutProducer;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 登记一条到期任务：{@code node} 决定到期后执行哪个动作，
     * {@code deadline} 是绝对到期时刻（由调用方按派单时间/验收时间算好）。
     */
    public void schedule(String node, long ticketId, long tenantId, Instant deadline) {
        try {
            String body = objectMapper.writeValueAsString(new TimeoutCommand(tenantId, ticketId, node));
            Message message = new Message(properties.getTimeoutTopic(), node,
                    body.getBytes(StandardCharsets.UTF_8));
            // 同一工单的消息用同一 key，便于排查；tenantId 放用户属性，消费端不用解析 body 也能路由
            message.setKeys(tenantId + "|" + ticketId);
            message.putUserProperty("tenantId", String.valueOf(tenantId));
            long deliverAt = Math.max(System.currentTimeMillis(), deadline.toEpochMilli());
            // RocketMQ 5.x timer 消息：到期由 broker 投递，精度 timerPrecisionMs（默认 1s）
            message.setDeliverTimeMs(deliverAt);
            SendResult result = timeoutProducer.send(message);
            log.info("超时任务已投递 RocketMQ node={} tenantId={} ticketId={} deliverAt={} msgId={}",
                    node, tenantId, ticketId, deliverAt, result.getMsgId());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "超时任务投递失败 node=" + node + " ticketId=" + ticketId, e);
        }
    }
}
