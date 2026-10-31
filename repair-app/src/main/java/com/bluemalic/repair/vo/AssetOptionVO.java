package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 学生提交报修时可选的设备（按房间列出，只给最小字段）。 */
@Data
@Schema(description = "可关联的设备（简）")
public class AssetOptionVO {

    private Long id;

    @Schema(description = "设备编号（贴在设备上的码就是它）")
    private String assetNo;

    private String name;

    private String room;
}
