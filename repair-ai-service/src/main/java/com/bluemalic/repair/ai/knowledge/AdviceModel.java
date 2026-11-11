package com.bluemalic.repair.ai.knowledge;

import java.util.List;

/**
 * "把工单 + 检索结果交给模型"这一层的抽象。
 *
 * <p>存在的理由与 {@code AgentModel} 一样：**让服务层可测**。集成测试里用一个假实现
 * （{@code src/test/java} 下的 {@code @Primary} Bean）就能把"引用核对""零命中不调模型"
 * 这些服务端逻辑全部跑起来，而不花钱、不联网。
 *
 * <p>它刻意做得很薄：组装提示词、发出去、把文本带回来；**不解析、不判断**——
 * 解析与核对都在服务层，那里才是可以被测住的地方。
 */
public interface AdviceModel {

    /**
     * @param description  工单描述
     * @param categoryName 类别名（可空）
     * @param hits         本次检索命中的条目（**非空**；调用方在零命中时根本不会调这里）
     * @return 模型原始返回（通常是 JSON，可能带 markdown 围栏）
     */
    String generate(String description, String categoryName, List<KnowledgeHit> hits);
}
