package com.bluemalic.repair.vo;

import com.bluemalic.repair.common.NumericLongSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 搬运校验的**逐表**明细（阶段 6 第 2 步 · ADR-021）。
 *
 * <p>为什么细到表：搬迁出错时"整体不一致"这句话没法用——要知道是哪张表、
 * 差在哪（行数不同 = 漏搬或源库又被写过；结构不同 = 两侧建表脚本漂开了）。
 */
@Data
@Schema(description = "逐表校验明细")
public class TenantDatabaseTableVO {

    @Schema(description = "表名")
    private String table;

    @Schema(description = "平台库里的行数")
    @JsonSerialize(using = NumericLongSerializer.class)
    private Long sourceRows;

    @Schema(description = "租户库里的行数")
    @JsonSerialize(using = NumericLongSerializer.class)
    private Long targetRows;

    @Schema(description = "两侧列结构是否一致（列名、顺序、类型）")
    private Boolean structureMatches;

    @Schema(description = "本表是否一致")
    private Boolean consistent;

    @Schema(description = "不一致时的说明（人话）")
    private String detail;
}
