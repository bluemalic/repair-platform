package com.bluemalic.repair.ai.agent;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * "问模型下一步做什么"——**编排循环与本接口之间只交换项目自己的类型**（{@link AgentStep} /
 * {@link AgentToolCall}），LangChain4j 只出现在这个接口的消息历史上。
 *
 * <p>这样切一刀的收益是可测：{@link AgentLoop} 的全部行为（步数上限、工具白名单、未知工具处理、
 * 轨迹记录）都能用一个"脚本化假模型"驱动，不需要网络、不花钱。真模型只剩"把消息转成
 * ChatRequest、把响应转回 AgentStep"这一层薄壳，靠一次真实调用验证。
 *
 * @see AgentLoop
 */
public interface AgentModel {

    /**
     * 让模型走一步。实现方**不要**在这里做步数控制或工具过滤——那是 {@link AgentLoop} 的职责，
     * 放在实现里会导致"换个模型就绕过了上限"。
     */
    AgentStep next(List<ChatMessage> messages);
}
