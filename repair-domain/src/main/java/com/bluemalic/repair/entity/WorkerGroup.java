package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工种分组（阶段 3 · docs/01 §4.8）：一个维修工可属多个组，派单时按类别的默认工种加分。 */
@Data
@TableName("worker_group")
public class WorkerGroup {

    private Long id;

    private Long tenantId;

    /** 工种名，如「水电工」「空调工」 */
    private String name;

    private Integer sort;

    /** 1启用 0停用 */
    private Integer status;

    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
