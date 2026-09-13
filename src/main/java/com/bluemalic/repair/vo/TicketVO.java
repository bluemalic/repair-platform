package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单列表项 / 通用工单返回体。 */
@Data
@Schema(description = "工单信息")
public class TicketVO {

    @Schema(description = "工单ID")
    private Long id;

    private String ticketNo;

    @Schema(description = "状态：10待派单 20待接单 30处理中 40待验收 50已完成 60已关闭 70已撤单 80已驳回")
    private Integer status;

    @Schema(description = "紧急度 1普通 2紧急 3特急")
    private Integer urgency;

    private Long buildingId;

    @Schema(description = "楼栋名称")
    private String buildingName;

    private String room;

    private Long categoryId;

    @Schema(description = "类别名称")
    private String categoryName;

    private Long studentId;

    private Long workerId;

    @Schema(description = "报修码对应的房间码（扫码报修时回显用）")
    private String repairCode;

    private LocalDateTime submitTime;

    private LocalDateTime dispatchTime;

    private LocalDateTime finishTime;

    private Integer arriveMinutes;

    private Integer handleMinutes;
}
