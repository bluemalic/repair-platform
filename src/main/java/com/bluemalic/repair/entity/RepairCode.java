package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 报修码（房间位置码，不是工单码）。字段与 repair_code 表一一对应。 */
@Data
@TableName("repair_code")
public class RepairCode {

    private Long id;

    private Long tenantId;

    /** 报修码，租户内唯一，扫码 / 手输用 */
    private String code;

    private Long buildingId;

    private String room;

    private Integer status;

    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
