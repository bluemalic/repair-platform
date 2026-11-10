package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bluemalic.repair.common.AuditAction;
import com.bluemalic.repair.common.AuditTarget;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.converter.AssetConverter;
import com.bluemalic.repair.converter.TicketConverter;
import com.bluemalic.repair.dto.AssetCreateDTO;
import com.bluemalic.repair.dto.AssetUpdateDTO;
import com.bluemalic.repair.entity.Asset;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.mapper.AssetMapper;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.AssetService;
import com.bluemalic.repair.service.AuditService;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.vo.AssetVO;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.TicketVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 设备台账实现。
 *
 * <p>租户条件显式写在每条查询上（asset 不在拦截器范围内，见 AGENTS §5.6）。
 * 类别/楼栋名在 Service 里一次性补全（列表按页批量查，详情查单条），前端不用自己换名称。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetServiceImpl implements AssetService {

    private static final int STATUS_IN_USE = 1;

    private final AssetMapper assetMapper;
    private final TicketMapper ticketMapper;
    private final BuildingMapper buildingMapper;
    private final TicketCategoryMapper ticketCategoryMapper;
    private final TicketReadModelAssembler readModelAssembler;
    private final CurrentTenantService currentTenantService;
    private final AuditService auditService;

    @Override
    public PageResult<AssetVO> page(long pageNum, long pageSize, Long buildingId, Long categoryId,
                                    Integer status, String keyword) {
        long tenantId = currentTenantService.requireTenantId();
        var query = Wrappers.<Asset>lambdaQuery()
                .eq(Asset::getTenantId, tenantId)
                .eq(buildingId != null, Asset::getBuildingId, buildingId)
                .eq(categoryId != null, Asset::getCategoryId, categoryId)
                .eq(status != null, Asset::getStatus, status);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            query.and(w -> w.like(Asset::getAssetNo, kw).or().like(Asset::getName, kw));
        }
        query.orderByDesc(Asset::getUpdateTime).orderByDesc(Asset::getId);

        Page<Asset> page = assetMapper.selectPage(new Page<>(pageNum, pageSize), query);
        List<AssetVO> vos = page.getRecords().stream().map(AssetConverter::toVO).toList();
        fillNames(vos, tenantId);
        Page<AssetVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(vos);
        return PageResult.of(voPage);
    }

    @Override
    public AssetVO detail(long id) {
        long tenantId = currentTenantService.requireTenantId();
        AssetVO vo = AssetConverter.toVO(requireAsset(id, tenantId));
        fillNames(List.of(vo), tenantId);
        return vo;
    }

    @Override
    @Transactional
    public AssetVO create(AssetCreateDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        requireAssetNoNotUsed(tenantId, dto.getAssetNo(), null);
        requireReferences(tenantId, dto.getBuildingId(), dto.getCategoryId());

        Asset asset = new Asset();
        asset.setTenantId(tenantId);
        asset.setAssetNo(dto.getAssetNo().trim());
        asset.setName(dto.getName().trim());
        asset.setCategoryId(dto.getCategoryId());
        asset.setBuildingId(dto.getBuildingId());
        asset.setRoom(dto.getRoom());
        asset.setStatus(STATUS_IN_USE);
        asset.setPurchaseDate(dto.getPurchaseDate());
        asset.setWarrantyUntil(dto.getWarrantyUntil());
        asset.setSupplier(dto.getSupplier());
        asset.setRemark(dto.getRemark());
        try {
            assetMapper.insert(asset);
        } catch (DuplicateKeyException e) {
            // 并发新增同一个编号时，先查后插的空档会被唯一索引兜住；这里翻译成同一条业务错误
            throw new BizException(ErrorCode.PARAM_INVALID, "设备编号已存在：" + asset.getAssetNo());
        }

        log.info("新增设备 assetId={} assetNo={} operator={}",
                asset.getId(), asset.getAssetNo(), StpUtil.getLoginIdAsLong());
        auditService.record(AuditAction.ASSET_CREATE, AuditTarget.ASSET, asset.getId(), asset.getAssetNo(),
                "新增设备 " + asset.getAssetNo() + "（" + asset.getName() + "）");
        AssetVO vo = AssetConverter.toVO(asset);
        fillNames(List.of(vo), tenantId);
        return vo;
    }

    @Override
    @Transactional
    public void update(long id, AssetUpdateDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        Asset before = requireAsset(id, tenantId);
        requireAssetNoNotUsed(tenantId, dto.getAssetNo(), id);
        requireReferences(tenantId, dto.getBuildingId(), dto.getCategoryId());

        int rows;
        try {
            rows = assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                    .eq(Asset::getId, id)
                    .eq(Asset::getTenantId, tenantId)
                    .set(Asset::getAssetNo, dto.getAssetNo().trim())
                    .set(Asset::getName, dto.getName().trim())
                    .set(Asset::getCategoryId, dto.getCategoryId())
                    .set(Asset::getBuildingId, dto.getBuildingId())
                    .set(Asset::getRoom, dto.getRoom())
                    .set(Asset::getStatus, dto.getStatus())
                    .set(Asset::getPurchaseDate, dto.getPurchaseDate())
                    .set(Asset::getWarrantyUntil, dto.getWarrantyUntil())
                    .set(Asset::getSupplier, dto.getSupplier())
                    .set(Asset::getRemark, dto.getRemark()));
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "设备编号已存在：" + dto.getAssetNo().trim());
        }
        if (rows == 0) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "设备不存在");
        }
        log.info("修改设备 assetId={} assetNo={} status={} operator={}",
                id, dto.getAssetNo(), dto.getStatus(), StpUtil.getLoginIdAsLong());
        auditService.record(AuditAction.ASSET_UPDATE, AuditTarget.ASSET, id, dto.getAssetNo(),
                "修改设备 " + before.getAssetNo()
                        + (before.getAssetNo().equals(dto.getAssetNo()) ? "" : "（改号为 " + dto.getAssetNo() + "）")
                        + "：状态 " + statusText(before.getStatus()) + "→" + statusText(dto.getStatus()));
    }

    @Override
    public PageResult<TicketVO> tickets(long assetId, long pageNum, long pageSize) {
        long tenantId = currentTenantService.requireTenantId();
        // 先确认设备属于本租户：否则拿到别家的 assetId 就能看到"这张设备在本租户下的工单"（空列表也会泄露 404/200 差异）
        requireAsset(assetId, tenantId);
        Page<Ticket> page = ticketMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<Ticket>lambdaQuery()
                        .eq(Ticket::getAssetId, assetId)
                        .orderByDesc(Ticket::getSubmitTime)
                        .orderByDesc(Ticket::getId));
        Map<Long, String> buildings = readModelAssembler.buildingNames(
                page.getRecords().stream().map(Ticket::getBuildingId).toList());
        Map<Long, String> categories = readModelAssembler.categoryNames(
                page.getRecords().stream().map(Ticket::getCategoryId).toList());
        List<TicketVO> vos = page.getRecords().stream()
                .map(t -> TicketConverter.toVO(t, buildings, categories)).toList();
        Page<TicketVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(vos);
        return PageResult.of(voPage);
    }

    @Override
    @Transactional
    public void delete(long id) {
        long tenantId = currentTenantService.requireTenantId();
        Asset asset = requireAsset(id, tenantId);

        Long referenced = ticketMapper.selectCount(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getAssetId, id));
        if (referenced != null && referenced > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "该设备已有 " + referenced + " 张工单，不能删除；下线请改为停用/报废");
        }
        // 显式带租户条件（asset 不在数据权限拦截器范围内）
        int rows = assetMapper.delete(Wrappers.<Asset>lambdaQuery()
                .eq(Asset::getId, id)
                .eq(Asset::getTenantId, tenantId));
        if (rows == 0) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "设备不存在");
        }
        log.info("删除设备 assetId={} assetNo={} operator={}",
                id, asset.getAssetNo(), StpUtil.getLoginIdAsLong());
        auditService.record(AuditAction.ASSET_DELETE, AuditTarget.ASSET, id, asset.getAssetNo(),
                "删除设备 " + asset.getAssetNo() + "（" + asset.getName() + "）");
    }

    private Asset requireAsset(long id, long tenantId) {
        return assetMapper.selectList(Wrappers.<Asset>lambdaQuery()
                        .eq(Asset::getId, id)
                        .eq(Asset::getTenantId, tenantId))
                .stream().findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND, "设备不存在"));
    }

    /** 楼栋 / 类别如果填了，必须是本租户已存在的行（防止拼出悬空引用）。 */
    private void requireReferences(long tenantId, Long buildingId, Long categoryId) {
        if (buildingId != null && buildingMapper.selectCount(Wrappers.<Building>lambdaQuery()
                .eq(Building::getId, buildingId)
                .eq(Building::getTenantId, tenantId)) == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "楼栋不存在：" + buildingId);
        }
        if (categoryId != null && ticketCategoryMapper.selectCount(Wrappers.<TicketCategory>lambdaQuery()
                .eq(TicketCategory::getId, categoryId)
                .eq(TicketCategory::getTenantId, tenantId)) == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "报修类别不存在：" + categoryId);
        }
    }

    private void requireAssetNoNotUsed(long tenantId, String assetNo, Long excludeId) {
        Long count = assetMapper.selectCount(Wrappers.<Asset>lambdaQuery()
                .eq(Asset::getTenantId, tenantId)
                .eq(Asset::getAssetNo, assetNo.trim())
                .ne(excludeId != null, Asset::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "设备编号已存在：" + assetNo.trim());
        }
    }

    /** 批量补类别/楼栋名：一次查两张表，避免 N+1。 */
    private void fillNames(List<AssetVO> vos, long tenantId) {
        if (vos.isEmpty()) {
            return;
        }
        Set<Long> categoryIds = vos.stream().map(AssetVO::getCategoryId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Set<Long> buildingIds = vos.stream().map(AssetVO::getBuildingId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());

        Map<Long, String> categoryNames = categoryIds.isEmpty() ? Map.of()
                : ticketCategoryMapper.selectList(Wrappers.<TicketCategory>lambdaQuery()
                        .eq(TicketCategory::getTenantId, tenantId)
                        .in(TicketCategory::getId, categoryIds))
                .stream().collect(Collectors.toMap(TicketCategory::getId, TicketCategory::getName));
        Map<Long, String> buildingNames = buildingIds.isEmpty() ? Map.of()
                : buildingMapper.selectList(Wrappers.<Building>lambdaQuery()
                        .eq(Building::getTenantId, tenantId)
                        .in(Building::getId, buildingIds))
                .stream().collect(Collectors.toMap(Building::getId, Building::getName));

        vos.forEach(vo -> {
            if (vo.getCategoryId() != null) {
                vo.setCategoryName(categoryNames.get(vo.getCategoryId()));
            }
            if (vo.getBuildingId() != null) {
                vo.setBuildingName(buildingNames.get(vo.getBuildingId()));
            }
        });
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 1 -> "在用";
            case 2 -> "维修中";
            case 3 -> "停用/报废";
            default -> "未知";
        };
    }
}
