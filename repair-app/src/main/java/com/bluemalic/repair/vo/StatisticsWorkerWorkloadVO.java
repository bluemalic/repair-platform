package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "师傅工作量与效率")
public class StatisticsWorkerWorkloadVO {

    @Schema(description = "维修工用户ID")
    private Long workerId;

    @Schema(description = "维修工姓名")
    private String workerName;

    @Schema(description = "区间内完工的工单数")
    private Integer finishedCount;

    @Schema(description = "平均处理时长（分钟）= 完工 − 到场")
    private Double avgHandleMinutes;

    @Schema(description = "触发过处理超时升级的工单数")
    private Integer processTimeoutCount;

    @Schema(description = "按时完成率（百分比 0-100，两位小数）；没有完工工单时为 null")
    private Double onTimeRate;
}
