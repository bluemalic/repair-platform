package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报修码的**管理端记录**（列表 / 生成 / 修改用）。
 *
 * <p>与 {@link RepairCodeVO} 的区别——两个概念不要混：
 * <ul>
 *   <li>{@code RepairCodeVO}：扫码后拿到的**位置**（楼栋 + 房间），跨端共用，不带码本身的状态与主键</li>
 *   <li>本类：后勤管理端看到的**这条码记录**（主键、码、状态、生成时间），改码时要用 {@link #id}</li>
 * </ul>
 *
 * <p>带 {@code buildingName}：列表要显示"1号楼 1-101"，前端不必为每行再查一次楼栋
 * （由调用方批量补名称，见 {@code RepairCodeConverter}）。
 */
@Data
@Schema(description = "报修码记录（管理端）")
public class RepairCodeDetailVO {

    @Schema(description = "报修码记录ID（**不是码本身**，修改时用它）")
    private Long id;

    @Schema(description = "报修码，6 位数字")
    private String code;

    @Schema(description = "楼栋ID")
    private Long buildingId;

    @Schema(description = "楼栋名称")
    private String buildingName;

    @Schema(description = "房间号")
    private String room;

    @Schema(description = "状态 1启用 0停用")
    private Integer status;

    @Schema(description = "生成时间")
    private LocalDateTime createTime;
}
