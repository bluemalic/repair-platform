package com.bluemalic.repair.ai.agent;

import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * 一个可以被模型调用的工具。
 *
 * <p><b>白名单是唯一的准入方式</b>（见 {@link AgentToolRegistry}）：不在清单里的名字，
 * 循环直接拒绝并把拒绝理由作为工具结果回给模型，不执行、不重试。
 *
 * <p><b>两条硬约束</b>（ADR-017）：
 * <ul>
 *   <li><b>只读</b>：工具不得产生写副作用。Agent 的产出是"草稿 / 建议"，落库由用户确认后走原有接口</li>
 *   <li><b>不暴露可枚举面</b>：例如报修码查询（6 位可猜、靠 `@RateLimit` 挡暴力枚举）不进白名单——
 *       交给模型等于让猜码绕过限流那一层</li>
 * </ul>
 *
 * <p>{@link #execute} 约定**不抛异常**：解析参数失败、查不到数据、甚至内部出错，都返回一句
 * 人话给模型（模型据此自己决定换个参数还是告诉用户）。抛异常会打断整轮对话，而模型对
 * "工具返回了一段解释"是有能力处理的。
 */
public interface AgentTool {

    /** 工具名。模型按它调用，所以一旦发布就不要改名（改了等于换了把工具）。 */
    String name();

    /** 给模型看的说明：**它决定模型什么时候用这个工具**，所以要写清"什么时候该调、能拿到什么"。 */
    String description();

    /** 参数 schema，交给模型生成 argumentsJson。 */
    ToolSpecification spec();

    /** 执行并返回给模型的文本结果。**不抛异常**（理由见类注释）。 */
    String execute(String argumentsJson);
}
