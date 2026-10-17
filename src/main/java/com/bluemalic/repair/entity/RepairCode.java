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

    /** 1 宿舍房间（room=房间号）/ 2 公共区域（room=区域名，如 1F-大厅；docs/01 §4.6） */
    private Integer locationType;

    private Integer status;

    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
