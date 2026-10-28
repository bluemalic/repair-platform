package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 维修工负责的楼栋 —— 数据权限的物理落点（见 ADR-002）。
 * 只有 createTime，没有 updateTime / deleted：解除负责关系就是删行。
 */
@Data
@TableName("worker_building")
public class WorkerBuilding {

    private Long id;

    private Long tenantId;

    private Long workerId;

    private Long buildingId;

    private LocalDateTime createTime;
}
