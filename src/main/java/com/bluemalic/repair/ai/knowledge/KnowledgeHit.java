package com.bluemalic.repair.ai.knowledge;

import lombok.Data;

/**
 * 一次检索命中的知识条目（含相关度分数）。
 *
 * <p><b>为什么不是 record</b>：它是 MyBatis 的查询返回类型，走 setter 映射最稳——
 * record 要靠"构造器参数名映射"，依赖编译期 {@code -parameters}，
 * 少一个参数或改个名就变成运行期悄悄少一个字段。
 *
 * <p>{@link #score} 是 {@code MATCH ... AGAINST} 给的相关度（关键词检索时），
 * 前端会把它显示成"匹配度"，所以它也是**可解释性**的一部分：师傅能看到建议是依据哪几条、
 * 哪条更贴题，而不是只看到一个结论。
 */
@Data
public class KnowledgeHit {

    private Long id;

    private Long categoryId;

    private String title;

    private String symptom;

    private String solution;

    private String tags;

    /** 相关度分数（越大越相关）。不同检索实现的分值不可比，只用于本次排序与展示 */
    private Double score;
}
