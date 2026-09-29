package com.bluemalic.repair.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bluemalic.repair.entity.TicketCollaborator;

/**
 * 工单协作者。**这张表不在数据权限拦截器的覆盖名单里**（名单只有 ticket / notification），
 * 所以这里出去的每条查询都要自己带范围条件——最常按 {@code ticket_id} 走（工单本身已经过拦截器），
 * 跨工单的统计类查询则显式带 {@code tenant_id}（AGENTS §5.6）。
 */
public interface TicketCollaboratorMapper extends BaseMapper<TicketCollaborator> {
}
