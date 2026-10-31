package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 维修工排班（阶段 3 · docs/01 §4.8）。
 *
 * <p>「没有记录」与「明确休息」是两件事：前者按缺数据记中性分，后者把候选排到最后。
 */
@Data
@TableName("worker_shift")
public class WorkerShift {

    private Long id;

    private Long tenantId;

    private Long workerId;

    private LocalDate shiftDate;

    /** 1白班 2夜班 */
    private Integer shiftType;

    /** 1在班 0请假/休息 */
    private Integer status;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
