package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 图表建议：由模型给出"这组数据怎么展示最合适"，前端照着画。
 *
 * <p>{@code x} / {@code y} 是**列别名**，与 {@link AiQueryVO#getColumns()} 里的名字对应——
 * 前端拿它找到对应的那两列，不需要猜。
 */
@Data
@Schema(description = "图表建议")
public class AiChartVO {

    @Schema(description = "图表类型 bar / line / pie / none（none = 只有一个数字，画不了图）")
    private String type;

    @Schema(description = "维度列的别名")
    private String x;

    @Schema(description = "数值列的别名")
    private String y;
}
