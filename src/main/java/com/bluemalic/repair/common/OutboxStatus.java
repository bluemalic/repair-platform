package com.bluemalic.repair.common;

/** Outbox 事件的投递状态。 */
public enum OutboxStatus {
    NEW,
    SENDING,
    SENT
}
