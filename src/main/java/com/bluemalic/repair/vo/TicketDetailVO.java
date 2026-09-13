package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/** 工单详情：基础信息 + 流转时间线 + 评价。 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工单详情")
public class TicketDetailVO extends TicketVO {

    private String description;

    private List<String> images;

    private String resultDesc;

    private List<String> resultImages;

    private String rejectReason;

    private LocalDateTime acceptTime;

    private LocalDateTime arriveTime;

    private LocalDateTime closeTime;

    @Schema(description = "流转时间线")
    private List<TicketLogVO> logs;

    @Schema(description = "验收评价，未评价为 null")
    private TicketEvaluationVO evaluation;
}
