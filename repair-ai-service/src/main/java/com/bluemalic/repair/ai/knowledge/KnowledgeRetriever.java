package com.bluemalic.repair.ai.knowledge;

import java.util.List;

/**
 * 知识检索的**唯一入口**（ADR-018 的第 2 条：关键词默认、向量留位）。
 *
 * <p>把它抽成接口，是为了让"换检索方式"成为换一个实现，而不是改一遍调用方：
 * 将来要做向量检索（前提条件见 ADR-018），只需要新增一个实现类 + 把配置
 * {@code repair.knowledge.retriever} 指过去，{@code KnowledgeAdviceServiceImpl} 一行都不用动。
 *
 * <p>换实现时**必须一起想清楚的三件事**（写在接口上，免得下一个实现忘了）：
 * <ol>
 *   <li>{@link #name()} 会原样回给前端（"这条建议是基于哪种检索"），别写成类名</li>
 *   <li>分数只在**同一个实现内**可比（关键词的相关度和向量的余弦值不是一回事），
 *       所以排序由实现负责，调用方不重排</li>
 *   <li>租户条件由实现自己保证：{@code knowledge_entry} **不在**数据权限拦截器的覆盖名单里
 *       （AGENTS §5.6），漏了就是跨租户读别校的知识库</li>
 * </ol>
 */
public interface KnowledgeRetriever {

    /**
     * 按一段自然语言（通常是工单描述）检索本租户**启用中**的知识条目。
     *
     * @param tenantId   当前租户
     * @param text       检索文本（工单描述）；空白时返回空列表，而不是"匹配所有"
     * @param categoryId 工单类别，可空。非空时只取"该类别 + 通用条目"，同类别条目排前面
     * @param limit      最多返回几条
     * @return 命中的条目（按相关度降序）；**没有命中就返回空列表**——调用方据此"如实说没有"
     */
    List<KnowledgeHit> search(long tenantId, String text, Long categoryId, int limit);

    /** 检索方式的名字，随响应回给前端：{@code keyword}（将来会有 {@code vector}）。 */
    String name();
}
