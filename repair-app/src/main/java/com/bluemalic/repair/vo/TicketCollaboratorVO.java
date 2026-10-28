package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 工单详情里的一个协作者（`docs/01` §4.5）。**只读展示**：加/移协作者是后台接口。 */
@Data
@Schema(description = "工单协作者")
public class TicketCollaboratorVO {

    @Schema(description = "维修工用户ID")
    private Long workerId;

    @Schema(description = "维修工姓名")
    private String workerName;
}
