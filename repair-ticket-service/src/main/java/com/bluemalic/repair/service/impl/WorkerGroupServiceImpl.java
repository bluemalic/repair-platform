package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.AuditAction;
import com.bluemalic.repair.common.AuditTarget;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.config.CacheConfig;
import com.bluemalic.repair.converter.WorkerGroupConverter;
import com.bluemalic.repair.dto.WorkerGroupCreateDTO;
import com.bluemalic.repair.dto.WorkerGroupUpdateDTO;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.entity.WorkerGroup;
import com.bluemalic.repair.entity.WorkerGroupMember;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.WorkerGroupMapper;
import com.bluemalic.repair.mapper.WorkerGroupMemberMapper;
import com.bluemalic.repair.service.AuditService;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.service.WorkerGroupService;
import com.bluemalic.repair.vo.WorkerGroupVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工种分组管理。
 *
 * <p>这三张表（worker_group / worker_group_member / ticket_category）都不在数据权限拦截器范围内，
 * 所以每条查询都显式带 tenant_id（AGENTS §5.6）。成员表的删除是物理删除（解除关系就是删行），
 * 与 worker_building 一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerGroupServiceImpl implements WorkerGroupService {

    private static final int STATUS_ENABLED = 1;

    private final WorkerGroupMapper workerGroupMapper;
    private final WorkerGroupMemberMapper memberMapper;
    private final SysUserMapper sysUserMapper;
    private final TicketCategoryMapper ticketCategoryMapper;
    private final CurrentTenantService currentTenantService;
    private final AuditService auditService;

    @Override
    public List<WorkerGroupVO> list() {
        long tenantId = currentTenantService.requireTenantId();
        List<WorkerGroup> groups = workerGroupMapper.selectList(Wrappers.<WorkerGroup>lambdaQuery()
                .eq(WorkerGroup::getTenantId, tenantId)
                .orderByAsc(WorkerGroup::getSort)
                .orderByAsc(WorkerGroup::getId));
        if (groups.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Long>> membersByGroup = memberMapper.selectList(
                        Wrappers.<WorkerGroupMember>lambdaQuery().eq(WorkerGroupMember::getTenantId, tenantId))
                .stream()
                .collect(Collectors.groupingBy(WorkerGroupMember::getGroupId,
                        Collectors.mapping(WorkerGroupMember::getWorkerId, Collectors.toList())));
        return groups.stream()
                .map(group -> WorkerGroupConverter.toVO(group,
                        membersByGroup.getOrDefault(group.getId(), List.of())))
                .toList();
    }

    @Override
    @Transactional
    public WorkerGroupVO create(WorkerGroupCreateDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        String name = dto.getName().trim();
        requireNameNotUsed(tenantId, name, null);

        WorkerGroup group = new WorkerGroup();
        group.setTenantId(tenantId);
        group.setName(name);
        group.setSort(dto.getSort() == null ? 0 : dto.getSort());
        group.setStatus(STATUS_ENABLED);
        workerGroupMapper.insert(group);

        log.info("新增工种分组 groupId={} name={} operator={}",
                group.getId(), group.getName(), StpUtil.getLoginIdAsLong());
        auditService.record(AuditAction.WORKER_GROUP_CREATE, AuditTarget.WORKER_GROUP,
                group.getId(), group.getName(), "新增工种分组 " + group.getName());
        return WorkerGroupConverter.toVO(group, List.of());
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.CACHE_ENABLED_CATEGORIES, allEntries = true)
    public void update(long id, WorkerGroupUpdateDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        WorkerGroup before = requireGroup(id, tenantId);
        String name = dto.getName().trim();
        requireNameNotUsed(tenantId, name, id);

        int rows = workerGroupMapper.update(null, Wrappers.<WorkerGroup>lambdaUpdate()
                .eq(WorkerGroup::getId, id)
                .eq(WorkerGroup::getTenantId, tenantId)
                .set(WorkerGroup::getName, name)
                .set(WorkerGroup::getSort, dto.getSort())
                .set(WorkerGroup::getStatus, dto.getStatus()));
        if (rows == 0) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "工种分组不存在");
        }
        log.info("修改工种分组 groupId={} name={} status={} operator={}",
                id, name, dto.getStatus(), StpUtil.getLoginIdAsLong());
        auditService.record(AuditAction.WORKER_GROUP_UPDATE, AuditTarget.WORKER_GROUP, id, name,
                "修改工种分组 " + before.getName() + (before.getName().equals(name) ? "" : "（改名为 " + name + "）")
                        + "：状态 " + statusText(before.getStatus()) + "→" + statusText(dto.getStatus()));
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.CACHE_ENABLED_CATEGORIES, allEntries = true)
    public void delete(long id) {
        long tenantId = currentTenantService.requireTenantId();
        WorkerGroup group = requireGroup(id, tenantId);

        Long members = memberMapper.selectCount(Wrappers.<WorkerGroupMember>lambdaQuery()
                .eq(WorkerGroupMember::getTenantId, tenantId)
                .eq(WorkerGroupMember::getGroupId, id));
        if (members != null && members > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该分组下还有维修工，请先移出组内成员");
        }
        Long categories = ticketCategoryMapper.selectCount(Wrappers.<TicketCategory>lambdaQuery()
                .eq(TicketCategory::getTenantId, tenantId)
                .eq(TicketCategory::getWorkerGroupId, id));
        if (categories != null && categories > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "还有报修类别使用该工种，请先在类别管理里改掉");
        }

        workerGroupMapper.deleteById(id);
        log.info("删除工种分组 groupId={} name={} operator={}",
                id, group.getName(), StpUtil.getLoginIdAsLong());
        auditService.record(AuditAction.WORKER_GROUP_DELETE, AuditTarget.WORKER_GROUP, id, group.getName(),
                "删除工种分组 " + group.getName());
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.CACHE_ENABLED_CATEGORIES, allEntries = true)
    public void setWorkers(long id, List<Long> workerIds) {
        long tenantId = currentTenantService.requireTenantId();
        WorkerGroup group = requireGroup(id, tenantId);

        List<Long> distinct = workerIds == null ? List.of()
                : workerIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (!distinct.isEmpty()) {
            List<SysUser> workers = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                    .eq(SysUser::getTenantId, tenantId)
                    .eq(SysUser::getUserType, UserType.WORKER.getCode())
                    .eq(SysUser::getStatus, 1)
                    .in(SysUser::getId, distinct));
            if (workers.size() != distinct.size()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "维修工不存在或已停用");
            }
        }

        memberMapper.delete(Wrappers.<WorkerGroupMember>lambdaQuery()
                .eq(WorkerGroupMember::getTenantId, tenantId)
                .eq(WorkerGroupMember::getGroupId, id));
        List<WorkerGroupMember> rows = new ArrayList<>(distinct.size());
        for (Long workerId : distinct) {
            WorkerGroupMember member = new WorkerGroupMember();
            member.setTenantId(tenantId);
            member.setWorkerId(workerId);
            member.setGroupId(id);
            rows.add(member);
        }
        rows.forEach(memberMapper::insert);

        log.info("设置工种分组成员 groupId={} name={} workers={} operator={}",
                id, group.getName(), distinct.size(), StpUtil.getLoginIdAsLong());
        auditService.record(AuditAction.WORKER_GROUP_WORKERS, AuditTarget.WORKER_GROUP, id, group.getName(),
                "设置工种分组 " + group.getName() + " 的成员：" + distinct.size() + " 人");
    }

    private WorkerGroup requireGroup(long id, long tenantId) {
        WorkerGroup group = workerGroupMapper.selectList(Wrappers.<WorkerGroup>lambdaQuery()
                        .eq(WorkerGroup::getId, id)
                        .eq(WorkerGroup::getTenantId, tenantId))
                .stream().findFirst().orElse(null);
        if (group == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "工种分组不存在");
        }
        return group;
    }

    /** 名称查重放应用层：与楼栋同一个理由（软删后仍占用名字的唯一索引会很难用）。 */
    private void requireNameNotUsed(long tenantId, String name, Long excludeId) {
        Long count = workerGroupMapper.selectCount(Wrappers.<WorkerGroup>lambdaQuery()
                .eq(WorkerGroup::getTenantId, tenantId)
                .eq(WorkerGroup::getName, name)
                .ne(excludeId != null, WorkerGroup::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "工种名称已存在");
        }
    }

    private String statusText(Integer status) {
        return Integer.valueOf(STATUS_ENABLED).equals(status) ? "启用" : "停用";
    }
}
