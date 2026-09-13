package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 扫报修码的返回体：学生端用来预填位置，维修工端用来比对是否到对了房间。 */
@Data
@Schema(description = "报修码对应的位置")
public class RepairCodeVO {

    private Long buildingId;

    @Schema(description = "楼栋名称")
    private String buildingName;

    @Schema(description = "房间号")
    private String room;
}
