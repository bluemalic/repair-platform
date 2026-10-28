package com.bluemalic.repair.vo;

import lombok.Data;

import java.util.List;

/**
 * 师傅端的排查建议（阶段 2 · ADR-018）。
 *
 * <p>与前两个 Agent 一致：**它是建议，不是状态变更**——这个接口一个字节都不写库。
 *
 * <p>三个字段体现"可追溯"：
 * <ul>
 *   <li>{@link #sources}：本次**检索命中的条目**（含相关度分数）——建议的依据</li>
 *   <li>{@link Step#citations}：每一步引用了几号条目（**服务端核对过**：引不到的一律剔除）</li>
 *   <li>{@link Step#cited}：这条步骤最终是否还有有效引用。为 {@code false} 表示模型写了一条
 *       "找不到来源"的步骤——前端要把它标出来，而不是混在别的步骤里装作有依据</li>
 * </ul>
 */
@Data
public class KnowledgeAdviceVO {

    /** 一句话：最可能是什么、先做什么（检索零命中时是固定的"知识库没有"话术） */
    private String suggestion;

    private List<Step> steps;

    /** 本次检索命中的知识条目，按相关度降序 */
    private List<Source> sources;

    /** 检索方式：{@code keyword}（将来会有 {@code vector}） */
    private String retriever;

    /**
     * 是否真的由模型生成过。
     * {@code false} = **检索零命中，没有调模型**（省钱，也让"没有依据"这件事对用户是可见的）。
     */
    private boolean generated;

    /** 一条排查步骤 */
    @Data
    public static class Step {

        private String text;

        /** 引用的知识条目 id；服务端只保留"确实在本次检索结果里"的那些 */
        private List<Long> citations;

        /** 是否还有有效引用 */
        private boolean cited;
    }

    /** 本次检索命中的条目 */
    @Data
    public static class Source {

        private Long id;

        private String title;

        private Double score;
    }
}
