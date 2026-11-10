package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "到场打卡入参")
public class TicketArriveDTO {

    @Schema(description = "现场扫到的报修码，服务端会校验与工单的楼栋房间一致")
    @NotBlank(message = "报修码不能为空")
    private String repairCode;
}
