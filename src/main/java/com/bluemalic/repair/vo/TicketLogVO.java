package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "工单流转记录")
public class TicketLogVO {

    private Long id;

    @Schema(description = "动作：SUBMIT/DISPATCH/ACCEPT/ARRIVE/FINISH/EVALUATE/CANCEL/CLOSE/REJECT")
    private String action;

    private Integer fromStatus;

    private Integer toStatus;

    private Long operatorId;

    @Schema(description = "操作人姓名")
    private String operatorName;

    private String remark;

    private LocalDateTime createTime;
}
