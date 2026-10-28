package com.bluemalic.repair.service;

/**
 * 往当前租户库的发件箱追加一条事件。
 *
 * <p>**必须在业务事务内调用**：事件行和业务变更一起提交，才能保证“业务成功 → 事件一定在”。
 * 调用方不要自己 try-catch 吞掉异常；插入失败应该让业务一起回滚。
 */
public interface OutboxService {

    /**
     * @param tenantId      租户 ID；系统上下文可传 0
     * @param aggregateType 聚合类型，如 TICKET
     * @param aggregateId   聚合 ID
     * @param eventType     事件类型，如 TICKET_STATUS_CHANGED
     * @param payload       可被 Jackson 序列化的载荷
     */
    void append(Long tenantId, String aggregateType, long aggregateId, String eventType, Object payload);
}
