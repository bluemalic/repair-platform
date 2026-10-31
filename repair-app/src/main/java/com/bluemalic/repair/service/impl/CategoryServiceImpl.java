package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.AuditAction;
import com.bluemalic.repair.config.CacheConfig;
import com.bluemalic.repair.common.AuditTarget;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.converter.CategoryConverter;
import com.bluemalic.repair.dto.CategoryCreateDTO;
import com.bluemalic.repair.dto.CategoryUpdateDTO;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.WorkerGroup;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.mapper.WorkerGroupMapper;
import com.bluemalic.repair.service.CategoryService;
import com.bluemalic.repair.service.AuditService;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.vo.CategoryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 报修类别管理。结构与 {@link BuildingServiceImpl} 相同，差别在"删除的前置校验"：
 * 类别只有 `ticket.category_id` 一处引用（楼栋还有报修码与师傅负责关系）。
 *
 * <p>另外注意：`TicketServiceImpl.submit` 会校验类别"存在 + 同租户 + 启用"，
 * 所以**停用的类别天然不能被新工单使用**，这是"下线"最省事的做法。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private static final int STATUS_ENABLED = 1;

    private final TicketCategoryMapper ticketCategoryMapper;
    private final TicketMapper ticketMapper;
    private final WorkerGroupMapper workerGroupMapper;
    private final CurrentTenantService currentTenantService;

    private final AuditService auditService;

    @Override
    public List<CategoryVO> list(Integer status) {
        long tenantId = currentTenantService.requireTenantId();
        List<CategoryVO> vos = ticketCategoryMapper.selectList(Wrappers.<TicketCategory>lambdaQuery()
                        .eq(TicketCategory::getTenantId, tenantId)
                        .eq(status != null, TicketCategory::getStatus, status)
                        .orderByAsc(TicketCategory::getSort)
                        .orderByAsc(TicketCategory::getId))
                .stream()
                .map(CategoryConverter::toVO)
                .toList();
        fillGroupNames(vos, tenantId);
        return vos;
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.CACHE_ENABLED_CATEGORIES, key = "#tenantId")
    public List<CategoryVO> listEnabled(long tenantId) {
        List<CategoryVO> vos = ticketCategoryMapper.selectList(Wrappers.<TicketCategory>lambdaQuery()
                        .eq(TicketCategory::getTenantId, tenantId)
                        .eq(TicketCategory::getStatus, STATUS_ENABLED)
                        .orderByAsc(TicketCategory::getSort)
                        .orderByAsc(TicketCategory::getId))
                .stream()
                .map(CategoryConverter::toVO)
                .toList();
        fillGroupNames(vos, tenantId);
        return vos;
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.CACHE_ENABLED_CATEGORIES, allEntries = true)
    public CategoryVO create(CategoryCreateDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        requireNameNotUsed(tenantId, dto.getName(), null);
        requireGroupIfPresent(dto.getWorkerGroupId(), tenantId);

        TicketCategory category = new TicketCategory();
        category.setTenantId(tenantId);
        category.setName(dto.getName());
        category.setDefaultUrgency(dto.getDefaultUrgency() == null ? 1 : dto.getDefaultUrgency());
        category.setSort(dto.getSort() == null ? 0 : dto.getSort());
        category.setWorkerGroupId(dto.getWorkerGroupId());
        category.setStatus(STATUS_ENABLED);
        ticketCategoryMapper.insert(category);

        log.info("新增报修类别 categoryId={} name={} operator={}",
                category.getId(), category.getName(), StpUtil.getLoginIdAsLong());
        auditService.record(AuditAction.CATEGORY_CREATE, AuditTarget.CATEGORY, category.getId(), category.getName(),
                "新增报修类别 " + category.getName() + "（默认紧急度 " + category.getDefaultUrgency() + "）");
        return CategoryConverter.toVO(category);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.CACHE_ENABLED_CATEGORIES, allEntries = true)
    public void update(long id, CategoryUpdateDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        TicketCategory before = requireCategory(id, tenantId);
        requireNameNotUsed(tenantId, dto.getName(), id);
        requireGroupIfPresent(dto.getWorkerGroupId(), tenantId);

        int rows = ticketCategoryMapper.update(null, Wrappers.<TicketCategory>lambdaUpdate()
                .eq(TicketCategory::getId, id)
                .eq(TicketCategory::getTenantId, tenantId)
                .set(TicketCategory::getName, dto.getName())
                .set(TicketCategory::getDefaultUrgency, dto.getDefaultUrgency())
                .set(TicketCategory::getSort, dto.getSort())
                .set(TicketCategory::getWorkerGroupId, dto.getWorkerGroupId())
                .set(TicketCategory::getStatus, dto.getStatus()));
        if (rows == 0) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "报修类别不存在");
        }
        log.info("修改报修类别 categoryId={} name={} status={} operator={}",
                id, dto.getName(), dto.getStatus(), StpUtil.getLoginIdAsLong());
        auditService.record(AuditAction.CATEGORY_UPDATE, AuditTarget.CATEGORY, id, dto.getName(),
                "修改报修类别 " + before.getName() + (before.getName().equals(dto.getName())
                        ? "" : "（改名为 " + dto.getName() + "）")
                        + "：状态 " + statusText(before.getStatus()) + "→" + statusText(dto.getStatus()));
    }

    private String statusText(Integer status) {
        return Integer.valueOf(STATUS_ENABLED).equals(status) ? "启用" : "停用";
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.CACHE_ENABLED_CATEGORIES, allEntries = true)
    public void delete(long id) {
        long tenantId = currentTenantService.requireTenantId();
        TicketCategory category = requireCategory(id, tenantId);

        // 被工单引用过的类别不能删：软删行会被全局逻辑删除从所有查询里滤掉，
        // 于是历史工单的类别名变成空白（同上，先查引用再删）
        Long used = ticketMapper.selectCount(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getCategoryId, id));
        if (used != null && used > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该类别下已有工单，不能删除；下线请改为停用");
        }

        ticketCategoryMapper.deleteById(id);
        log.info("删除未被引用的报修类别 categoryId={} name={} operator={}",
                id, category.getName(), StpUtil.getLoginIdAsLong());
        auditService.record(AuditAction.CATEGORY_DELETE, AuditTarget.CATEGORY, id, category.getName(),
                "删除报修类别 " + category.getName() + "（逻辑删除，行仍在库里便于查证）");
    }

    /** 工种分组必须是本租户的；传 null 表示不设置（或清空）。 */
    private void requireGroupIfPresent(Long groupId, long tenantId) {
        if (groupId == null) {
            return;
        }
        Long count = workerGroupMapper.selectCount(Wrappers.<WorkerGroup>lambdaQuery()
                .eq(WorkerGroup::getId, groupId)
                .eq(WorkerGroup::getTenantId, tenantId));
        if (count == null || count == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "工种分组不存在");
        }
    }

    /** 批量补工种名：一次查询，不做 N+1；分组被删时名称为 null，id 仍保留。 */
    private void fillGroupNames(List<CategoryVO> vos, long tenantId) {
        List<Long> groupIds = vos.stream().map(CategoryVO::getWorkerGroupId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (groupIds.isEmpty()) {
            return;
        }
        var names = workerGroupMapper.selectList(Wrappers.<WorkerGroup>lambdaQuery()
                        .eq(WorkerGroup::getTenantId, tenantId)
                        .in(WorkerGroup::getId, groupIds))
                .stream().collect(java.util.stream.Collectors.toMap(WorkerGroup::getId, WorkerGroup::getName));
        vos.forEach(vo -> {
            if (vo.getWorkerGroupId() != null) {
                vo.setWorkerGroupName(names.get(vo.getWorkerGroupId()));
            }
        });
    }

    /** 取本租户的类别，不存在一律说"不存在"。 */
    private TicketCategory requireCategory(long id, long tenantId) {
        TicketCategory category = ticketCategoryMapper.selectOne(Wrappers.<TicketCategory>lambdaQuery()
                .eq(TicketCategory::getId, id)
                .eq(TicketCategory::getTenantId, tenantId));
        if (category == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "报修类别不存在");
        }
        return category;
    }

    /** 名称查重；与楼栋同理，唯一性只能放在应用层（见 BuildingServiceImpl 的说明）。 */
    private void requireNameNotUsed(long tenantId, String name, Long excludeId) {
        Long count = ticketCategoryMapper.selectCount(Wrappers.<TicketCategory>lambdaQuery()
                .eq(TicketCategory::getTenantId, tenantId)
                .eq(TicketCategory::getName, name)
                .ne(excludeId != null, TicketCategory::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "类别名称已存在");
        }
    }
}
