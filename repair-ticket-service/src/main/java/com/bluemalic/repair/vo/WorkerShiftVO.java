package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/** 排班行（阶段 3 · docs/01 §4.8）。 */
@Data
@Schema(description = "排班")
public class WorkerShiftVO {

    private Long id;

    private Long workerId;

    /** 工号 */
    private String username;

    private String workerName;

    private LocalDate shiftDate;

    /** 1白班 2夜班 */
    private Integer shiftType;

    /** 1在班 0请假/休息 */
    private Integer status;

    private String remark;
}
