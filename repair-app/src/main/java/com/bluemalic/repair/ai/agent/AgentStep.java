package com.bluemalic.repair.ai.agent;

import java.util.List;

/**
 * 模型的**一步**：要么说点什么，要么要求调工具，也可能两者都有（边说边调）。
 *
 * <p>刻意用项目自己的类型而不是 LangChain4j 的 {@code AiMessage}：编排循环只依赖本接口
 * （见 {@link AgentModel}），于是测试可以塞一个"脚本化的假模型"——按预先写好的步骤序列返回，
 * 整条循环不花钱、不需要网络就能测。这与 {@code AiAssistant} 可替换成假实现是同一个思路。
 */
public record AgentStep(String text, List<AgentToolCall> toolCalls) {

    public AgentStep {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        text = text == null ? "" : text;
    }

    /** 不需要调工具 = 这一轮模型给出了最终回答。 */
    public boolean isFinal() {
        return toolCalls.isEmpty();
    }
}
