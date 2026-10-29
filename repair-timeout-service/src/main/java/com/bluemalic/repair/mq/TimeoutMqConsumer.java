package com.bluemalic.repair.mq;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.service.DueTicket;
import com.bluemalic.repair.service.TimeoutCommand;
import com.bluemalic.repair.service.impl.TicketTimeoutHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 超时到期消息的消费者（阶段 2 · ADR-024）：按节点调用 {@link TicketTimeoutHandler} 的三个动作。
 *
 * <p>幂等在处理器里：{@code notifyTimeoutOnce} 按状态 + ticket_log 判重，自动关闭是状态 CAS。
 * 因此**重复投递/迟到投递不会重复提醒或重复关闭**——这也是 MQ 模式下取消只能是"逻辑取消"的底气。
 *
 * <p>失败语义：坏消息（JSON 解析失败、未知节点）记日志后消费掉（重试不会成功）；
 * 业务异常（工单已流转）视为正常跳过；其余异常返回 {@code RECONSUME_LATER} 交给 broker 重投。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "repair.timeout", name = "delivery", havingValue = "mq")
public class TimeoutMqConsumer {

    private final TicketTimeoutHandler handler;
    private final ObjectMapper objectMapper;

    public ConsumeConcurrentlyStatus consume(List<MessageExt> messages) {
        for (MessageExt message : messages) {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            TimeoutCommand command;
            try {
                command = objectMapper.readValue(body, TimeoutCommand.class);
            } catch (JsonProcessingException e) {
                log.warn("超时消息解析失败，直接消费 keys={} body={}", message.getKeys(), body, e);
                continue;
            }
            if (command.node() == null || !isKnownNode(command.node())) {
                log.warn("超时消息节点未知，直接消费 keys={} body={}", message.getKeys(), body);
                continue;
            }

            DueTicket due = new DueTicket(command.tenantId(), command.ticketId());
            try {
                dispatch(command.node(), due);
            } catch (BizException e) {
                // 状态已流转 / 已被人工处理：正常跳过，不算失败（与 zset 调度器同一口径）
                log.info("超时任务跳过（工单已流转） node={} tenantId={} ticketId={} reason={}",
                        command.node(), command.tenantId(), command.ticketId(), e.getMessage());
            } catch (Exception e) {
                log.warn("超时任务处理失败，交给 MQ 重投 node={} tenantId={} ticketId={}",
                        command.node(), command.tenantId(), command.ticketId(), e);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    private void dispatch(String node, DueTicket due) {
        switch (node) {
            case TimeoutCommand.NODE_ACCEPT -> handler.remindAcceptTimeout(due);
            case TimeoutCommand.NODE_PROCESS -> handler.escalateProcessTimeout(due);
            case TimeoutCommand.NODE_EVAL -> handler.autoClose(due);
            default -> throw new IllegalStateException("未知超时节点: " + node);
        }
    }

    private boolean isKnownNode(String node) {
        return TimeoutCommand.NODE_ACCEPT.equals(node)
                || TimeoutCommand.NODE_PROCESS.equals(node)
                || TimeoutCommand.NODE_EVAL.equals(node);
    }
}
