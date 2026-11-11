package com.bluemalic.repair.ai.agent;

import com.bluemalic.repair.ai.AiChatModelProvider;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link AgentModel} 的真实实现：把消息与工具清单交给模型，再把回复翻译成 {@link AgentStep}。
 *
 * <p>刻意做得**很薄**：除了"组装请求 / 解析响应"，它不做任何决策（步数、白名单、重试都在
 * {@link AgentLoop} 里）。薄到可以靠一次真实调用验证——而循环的复杂逻辑全部由假模型在单测里覆盖。
 */
@Component
public class LangChainAgentModel implements AgentModel {

    private final AiChatModelProvider provider;

    /** 工具清单在构造时定死：白名单是会变的（改代码才会变），没必要每次调用重新组装。 */
    private final List<ToolSpecification> tools;

    public LangChainAgentModel(AiChatModelProvider provider, AgentToolRegistry registry) {
        this.provider = provider;
        this.tools = registry.specs();
    }

    @Override
    public AgentStep next(List<ChatMessage> messages) {
        return provider.call("Agent 编排", () -> {
            ChatResponse response = provider.get().chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(tools)
                    .build());
            AiMessage message = response.aiMessage();
            List<AgentToolCall> calls = message.toolExecutionRequests() == null
                    ? List.of()
                    : message.toolExecutionRequests().stream()
                            .map(r -> new AgentToolCall(r.id(), r.name(), r.arguments()))
                            .toList();
            return new AgentStep(message.text(), calls);
        });
    }
}
