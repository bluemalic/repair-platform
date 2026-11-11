package com.bluemalic.repair.service;

import com.bluemalic.repair.ai.AiQueryResult;
import com.bluemalic.repair.vo.AiChartVO;

/**
 * 问数过程中"中间结果"的去处：**编排只多这一个出口，流式与否由调用方决定**。
 *
 * <p>为什么不是给流式单写一条编排：四步链路（生成 → 网关 → 执行 → 结论）里的每一步顺序、
 * 校验、降级规则都只有一份（{@link AiQueryService}）；流式与非流式的差别仅仅是"中间结果要不要
 * 立刻给用户看"。非流式路径传 {@link #NONE}，一个字段都不多。
 *
 * <p>两段的先后**由编排保证**：{@link #sqlReady} 一定在 {@link #dataReady} 之前，且只有在前一步
 * 成功之后才会有下一步——SQL 被安全网关拦下时，一段都不会回调。
 */
public interface AiQueryProgress {

    /** 什么都不做：非流式路径用（它只要最终结果）。 */
    AiQueryProgress NONE = new AiQueryProgress() {

        @Override
        public void sqlReady(String sql, AiChartVO chart) {
        }

        @Override
        public void dataReady(AiQueryResult data) {
        }
    };

    /**
     * SQL 已生成**并通过安全网关**（此时还没有数据）。传出去的是注入过租户条件、真正要执行的那条，
     * 不是模型的原话——用户看到的必须与实际执行的一致，否则"返回 SQL 供人工复核"这件事就没意义了。
     *
     * @param chart 图表建议（前端据此决定画不画、画哪种）
     */
    void sqlReady(String sql, AiChartVO chart);

    /** 数据已取到，表格与图可以画了；结论还在路上（它要等第二次模型调用）。 */
    void dataReady(AiQueryResult data);
}
