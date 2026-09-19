package com.bluemalic.repair.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * 演示重置专用的物理删除。
 *
 * <p><b>为什么不用 {@code BaseMapper.delete()}</b>：`ticket` / `ticket_evaluation` 这些表带
 * {@code deleted} 逻辑删除字段，而 MP 的全局配置会把 {@code delete()} 变成
 * {@code UPDATE ... SET deleted = 1}。演示重置要的是"把这些行真的清掉"——每天逻辑删一遍，
 * 表会一天比一天大、而且旧数据仍然占着唯一索引（比如 `uk_ticket_no`），迟早撞键。
 *
 * <p>SQL 单独放在这个 Mapper 里，不塞进 TicketMapper：它是**演示部署专用**的破坏性操作，
 * 跟业务查询混在一起，哪天有人复用错地方就是一次数据事故。
 */
public interface DemoResetMapper {

    int deleteTickets(@Param("tenantId") long tenantId);

    int deleteTicketLogs(@Param("tenantId") long tenantId);

    int deleteEvaluations(@Param("tenantId") long tenantId);

    int deleteNotifications(@Param("tenantId") long tenantId);

    int deleteWorkerBuildings(@Param("tenantId") long tenantId);
}
