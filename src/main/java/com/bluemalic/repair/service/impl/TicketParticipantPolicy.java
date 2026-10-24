package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketCollaborator;
import com.bluemalic.repair.mapper.TicketCollaboratorMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工单参与人判定：主责、协作者，以及“我是不是这单的参与人”。
 *
 * <p>协作者能到场、能完工；接单与驳回仍只给主责——那两件事是“我认领这单”和
 * “这单不该我做”，属于处置权（`docs/01` §4.5）。
 */
@Component
@RequiredArgsConstructor
class TicketParticipantPolicy {

    private final TicketCollaboratorMapper ticketCollaboratorMapper;

    boolean isNotAssignee(Ticket ticket, long workerId) {
        return ticket.getWorkerId() == null || ticket.getWorkerId() != workerId;
    }

    boolean isNotParticipant(Ticket ticket, long workerId) {
        return isNotAssignee(ticket, workerId) && !isCollaborator(ticket, workerId);
    }

    /** 这个人在不在这单的协作者名单里。带 tenant_id 条件——防御纵深。 */
    boolean isCollaborator(Ticket ticket, long workerId) {
        return ticketCollaboratorMapper.selectCount(Wrappers.<TicketCollaborator>lambdaQuery()
                .eq(TicketCollaborator::getTenantId, ticket.getTenantId())
                .eq(TicketCollaborator::getTicketId, ticket.getId())
                .eq(TicketCollaborator::getWorkerId, workerId)) > 0;
    }

    long collaboratorCount(Ticket ticket) {
        return ticketCollaboratorMapper.selectCount(Wrappers.<TicketCollaborator>lambdaQuery()
                .eq(TicketCollaborator::getTenantId, ticket.getTenantId())
                .eq(TicketCollaborator::getTicketId, ticket.getId()));
    }

    /** 到场/完工的条件更新里“主责或协作者”的 SQL 片段。 */
    void appendParticipantCondition(LambdaUpdateWrapper<Ticket> wrapper, long workerId) {
        wrapper.and(w -> w.eq(Ticket::getWorkerId, workerId)
                .or().exists("SELECT 1 FROM ticket_collaborator c"
                        + " WHERE c.ticket_id = ticket.id AND c.worker_id = {0}", workerId));
    }
}
