package com.bluemalic.repair.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bluemalic.repair.entity.NotificationDelivery;

/**
 * 通知投递记录 Mapper（阶段 4）。
 *
 * <p>这张表**不在数据权限拦截器的名单里**（只有 ticket / notification 在），
 * 所以按租户查时必须显式写 {@code tenant_id} —— 见 AGENTS §5.6。
 */
public interface NotificationDeliveryMapper extends BaseMapper<NotificationDelivery> {
}
