package com.bluemalic.repair.converter;

import com.bluemalic.repair.entity.Asset;
import com.bluemalic.repair.vo.AssetVO;

/** {@code Asset} 实体 → {@link AssetVO}。类别/楼栋名由 Service 统一补，转换器只做 1:1 映射。 */
public class AssetConverter {

    private AssetConverter() {
    }

    public static AssetVO toVO(Asset asset) {
        if (asset == null) {
            return null;
        }
        AssetVO vo = new AssetVO();
        vo.setId(asset.getId());
        vo.setAssetNo(asset.getAssetNo());
        vo.setName(asset.getName());
        vo.setCategoryId(asset.getCategoryId());
        vo.setBuildingId(asset.getBuildingId());
        vo.setRoom(asset.getRoom());
        vo.setStatus(asset.getStatus());
        vo.setPurchaseDate(asset.getPurchaseDate());
        vo.setWarrantyUntil(asset.getWarrantyUntil());
        vo.setSupplier(asset.getSupplier());
        vo.setRemark(asset.getRemark());
        vo.setCreateTime(asset.getCreateTime());
        return vo;
    }
}
