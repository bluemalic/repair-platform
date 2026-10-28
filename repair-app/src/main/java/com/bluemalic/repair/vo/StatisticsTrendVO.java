package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "报修量趋势的一个点")
public class StatisticsTrendVO {

    @Schema(description = "日期（yyyy-MM-dd）；按周时是那一周的周一")
    private String date;

    @Schema(description = "该区间的报修量")
    private Integer count;
}
