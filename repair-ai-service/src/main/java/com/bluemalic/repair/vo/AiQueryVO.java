package com.bluemalic.repair.vo;

import com.bluemalic.repair.common.NumericLongSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * AI 问数的结果（契约见 docs/03 §5.6）。
 *
 * <p>{@code sql} 一并返回是**有意的**：大模型生成的 SQL 不保证正确，把它给用户看，才能让人复核
 * "这个数字是怎么算出来的"。这也是排障时最有用的一个字段。
 */
@Data
@Schema(description = "AI 问数结果")
public class AiQueryVO {

    @Schema(description = "原问题")
    private String question;

    @Schema(description = "实际执行的 SQL（已注入租户条件、已过安全网关），供人工复核")
    private String sql;

    @Schema(description = "列名（中文，来自 SQL 里的别名）")
    private List<String> columns;

    /** 二维数据：与 {@code columns} 一一对应。数字是数字（不是字符串），前端直接拿去画图。 */
    @Schema(description = "结果行，每行与 columns 等长")
    private List<List<Object>> rows;

    @Schema(description = "图表建议")
    private AiChartVO chart;

    @Schema(description = "一句话结论（模型根据查询结果生成）")
    private String conclusion;

    @Schema(description = "结果是否因为行数上限被截断")
    private Boolean rowLimited;

    /**
     * 总耗时（毫秒）。按**数字**输出（与分页计数同一处理）：它是给人看的耗时，不是 ID，
     * 不需要防精度丢失——而全局规则会把 {@code Long} 变成字符串。
     */
    @JsonSerialize(using = NumericLongSerializer.class)
    @Schema(description = "总耗时（毫秒），含两次模型调用")
    private Long elapsedMs;
}
