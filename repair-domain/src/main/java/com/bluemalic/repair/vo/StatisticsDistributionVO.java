package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "分布统计的一项")
public class StatisticsDistributionVO {

    @Schema(description = "维度名称（类别名 / 楼栋名 / 紧急度文案）")
    private String name;

    @Schema(description = "该维度的工单数")
    private Integer count;
}
