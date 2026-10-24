package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.Texts;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.entity.TicketCollaborator;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketCollaboratorMapper;
import com.bluemalic.repair.vo.TicketCollaboratorVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工单读模型的批量装配：楼栋名、类别名、协作者名单与“我协作的工单”。
 *
 * <p>全部一次查询取全，不做 N+1；只读，不参与状态流转。
 */
@Component
@RequiredArgsConstructor
class TicketReadModelAssembler {

    private final BuildingMapper buildingMapper;
    private final TicketCategoryMapper ticketCategoryMapper;
    private final TicketCollaboratorMapper ticketCollaboratorMapper;
    private final SysUserMapper sysUserMapper;

    /** 批量 id → 楼栋名（selectByIds 一次拿全，避免循环回库的 N+1）。 */
    Map<Long, String> buildingNames(List<Long> ids) {
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return buildingMapper.selectByIds(distinct).stream()
                .collect(Collectors.toMap(Building::getId, Building::getName));
    }

    Map<Long, String> categoryNames(List<Long> ids) {
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return ticketCategoryMapper.selectByIds(distinct).stream()
                .collect(Collectors.toMap(TicketCategory::getId, TicketCategory::getName));
    }

    /** 详情里的协作者名单：一次查询 + 一次批量取姓名，不做 N+1。 */
    List<TicketCollaboratorVO> collaboratorsOf(Ticket ticket) {
        List<TicketCollaborator> rows = ticketCollaboratorMapper.selectList(
                Wrappers.<TicketCollaborator>lambdaQuery()
                        .eq(TicketCollaborator::getTenantId, ticket.getTenantId())
                        .eq(TicketCollaborator::getTicketId, ticket.getId())
                        .orderByAsc(TicketCollaborator::getCreateTime));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = sysUserMapper.selectByIds(
                        rows.stream().map(TicketCollaborator::getWorkerId).distinct().toList()).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> Texts.nullToEmpty(u.getRealName())));
        return rows.stream().map(row -> {
            TicketCollaboratorVO vo = new TicketCollaboratorVO();
            vo.setWorkerId(row.getWorkerId());
            vo.setWorkerName(names.get(row.getWorkerId()));
            return vo;
        }).toList();
    }

    /**
     * 本页里“我参与协作”的工单 ID（给列表打「协作」标记用）。一次查询，不逐条回库。
     *
     * <p>条件带 tenant_id 做防御纵深；工单 ID 取自已经过租户过滤的一页。
     */
    Set<Long> collaboratedTicketIds(Long tenantId, long workerId, List<Long> ticketIds) {
        if (ticketIds.isEmpty()) {
            return Set.of();
        }
        return ticketCollaboratorMapper.selectList(Wrappers.<TicketCollaborator>lambdaQuery()
                        .eq(TicketCollaborator::getTenantId, tenantId)
                        .eq(TicketCollaborator::getWorkerId, workerId)
                        .in(TicketCollaborator::getTicketId, ticketIds))
                .stream().map(TicketCollaborator::getTicketId).collect(Collectors.toSet());
    }
}
