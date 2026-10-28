package com.bluemalic.repair.vo;

import com.bluemalic.repair.common.NumericLongSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 某所学校的**数据在哪、搬家搬得怎么样**（阶段 6 第 2 步 · ADR-021）。
 *
 * <p>平台运营用它回答两个问题：①这所学校的数据现在在平台库还是它自己的库里；
 * ②如果搬过，源库与目标库是不是逐表一致（不一致就先别切）。
 */
@Data
@Schema(description = "学校的数据库形态与搬迁校验结果")
public class TenantDatabaseVO {

    private Long tenantId;

    @Schema(description = "学校编码")
    private String code;

    @Schema(description = "SHARED=数据在平台库（还没搬家）；DEDICATED=已经在它自己的库里")
    private String mode;

    /** 租户库名；还没搬家时为 null */
    private String dbSchema;

    @Schema(description = "目标库是否已经建好（搬过一次就会有）")
    private Boolean targetReady;

    @Schema(description = "源库与目标库是否**逐表一致**（结构 + 行数 + 全列校验和）；没搬过时为 null")
    private Boolean consistent;

    @Schema(description = "逐表明细；没搬过时为空")
    private java.util.List<TenantDatabaseTableVO> tables;

    /** 校验时刻 */
    private java.time.LocalDateTime checkedAt;

    /** 行数用数字输出（分页/计数类字段的例外，见 NumericLongSerializer） */
    @Schema(description = "源库（平台库）里的总行数")
    @JsonSerialize(using = NumericLongSerializer.class)
    private Long sourceRows;

    @Schema(description = "目标库（租户库）里的总行数")
    @JsonSerialize(using = NumericLongSerializer.class)
    private Long targetRows;
}
