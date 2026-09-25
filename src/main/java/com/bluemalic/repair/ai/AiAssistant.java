package com.bluemalic.repair.ai;

/**
 * 借助大模型做的两件事：把问题变成查询、把结果说成一句话。
 *
 * <p>抽成接口是为了**让编排可测**：{@code AiQueryService} 依赖它而不是直接依赖 LangChain4j，
 * 测试就能塞一个假实现进去——于是"生成 → 校验 → 执行 → 结论"整条链路都能在 CI 上跑，
 * 既不需要真的调模型，也不花一分钱。真正调模型的那部分（{@link LangChainAiAssistant}）
 * 只剩下提示词与异常翻译，薄到可以靠一次真实调用验证。
 */
public interface AiAssistant {

    /** 出查询计划。模型答不上来（没生成 SQL）时由实现方决定抛什么。 */
    QueryPlan plan(String question);

    /**
     * 根据问题与**实际查询结果**出一句结论。
     *
     * <p>为什么必须是第二次调用：模型没看到数据就写不出"共 42 单"这种结论——一次调用只能给出
     * "这将返回各楼栋的报修量"这种空话。
     */
    String summarize(String question, AiQueryResult result);
}
