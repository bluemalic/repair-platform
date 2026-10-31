package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 设备台账（阶段 3 · docs/01 §4.8）。租户内的资产数据，字段与 asset 表一一对应。
 *
 * <p>设备编号 {@code assetNo} 在租户内唯一，同时是二维码的内容——不单独存二维码字段。
 */
@Data
@TableName("asset")
public class Asset {

    private Long id;

    private Long tenantId;

    private String assetNo;

    private String name;

    private Long categoryId;

    private Long buildingId;

    private String room;

    /** 1 在用 / 2 维修中 / 3 停用或报废 */
    private Integer status;

    private LocalDate purchaseDate;

    private LocalDate warrantyUntil;

    private String supplier;

    private String remark;

    /** 逻辑删除 0否 1是。名字必须叫 deleted，全局配置按这个名字识别，改名会导致逻辑删除失效 */
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
