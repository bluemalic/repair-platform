package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.entity.Asset;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.RepairCode;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.mapper.AssetMapper;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.RepairCodeMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工单引用的基础数据校验：报修码、楼栋、类别。
 *
 * <p>对外不区分“不存在 / 别家租户 / 已停用”，避免向调用方泄露别家数据是否存在。
 * 这几条校验是提交、拆单、扫码到场共用的，收在一处避免两条路径各写一份后漂移。
 */
@Component
@RequiredArgsConstructor
class TicketReferenceValidator {

    private final RepairCodeMapper repairCodeMapper;
    private final AssetMapper assetMapper;
    private final BuildingMapper buildingMapper;
    private final TicketCategoryMapper ticketCategoryMapper;

    RepairCode requireRepairCode(String code, Long tenantId) {
        RepairCode repairCode = repairCodeMapper.selectOne(Wrappers.<RepairCode>lambdaQuery()
                .eq(RepairCode::getTenantId, tenantId)
                .eq(RepairCode::getCode, code)
                .eq(RepairCode::getStatus, 1));
        if (repairCode == null) {
            throw new BizException(ErrorCode.REPAIR_CODE_INVALID);
        }
        return repairCode;
    }

    /**
     * 楼栋必须是本租户下、启用中的。校验对象是“最终落到工单上的那个 buildingId”，
     * 所以扫码与手选两条提交路径都要过——否则能建出“后勤看得到、师傅永远看不到”的孤儿工单。
     */
    void requireEnabledBuilding(Long buildingId, Long tenantId) {
        Building building = buildingMapper.selectById(buildingId);
        if (building == null || !building.getTenantId().equals(tenantId)
                || !Integer.valueOf(1).equals(building.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "楼栋不存在或已停用");
        }
    }

    /**
     * 设备是**可选**的：没传返回 null；传了就必须是本租户的、且未停用/报废（3）。
     *
     * <p>不区分"不存在 / 别家租户 / 已停用"，避免向调用方泄露别家设备是否存在。
     */
    Asset requireUsableAsset(Long assetId, Long tenantId) {
        if (assetId == null) {
            return null;
        }
        Asset asset = assetMapper.selectList(Wrappers.<Asset>lambdaQuery()
                        .eq(Asset::getId, assetId)
                        .eq(Asset::getTenantId, tenantId))
                .stream().findFirst().orElse(null);
        if (asset == null || Integer.valueOf(3).equals(asset.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "设备不存在或已停用");
        }
        return asset;
    }

    /** 类别必须是本租户下、启用中的；提交报修与拆单共用。 */
    TicketCategory requireEnabledCategory(Long categoryId, Long tenantId) {
        TicketCategory category = ticketCategoryMapper.selectById(categoryId);
        if (category == null || !category.getTenantId().equals(tenantId)
                || !Integer.valueOf(1).equals(category.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "报修类别不存在或已停用");
        }
        return category;
    }
}
