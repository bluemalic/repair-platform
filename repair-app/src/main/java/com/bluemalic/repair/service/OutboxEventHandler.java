package com.bluemalic.repair.service;

import com.bluemalic.repair.entity.OutboxEvent;

/**
 * Outbox 事件的本地消费者。
 *
 * <p>阶段 1 只提供一个记录日志的占位实现；阶段 2 抽通知/超时服务时，
 * 把对应消费者注册成 Bean 即可，relay 不需要改。
 */
public interface OutboxEventHandler {

    boolean supports(String eventType);

    void handle(OutboxEvent event);
}
