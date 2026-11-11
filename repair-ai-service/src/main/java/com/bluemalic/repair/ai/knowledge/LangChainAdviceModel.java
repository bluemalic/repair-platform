package com.bluemalic.repair.ai.knowledge;

import com.bluemalic.repair.ai.AiChatModelProvider;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link AdviceModel} 的真实实现：把提示词发给模型，把文本带回来。
 *
 * <p>与 {@code LangChainAgentModel} 一样薄——它是**唯一需要联网的那一层**，
 * 所以薄到可以用一次真实调用验证；所有判断都在服务层，由假模型在单测/集成测试里覆盖。
 *
 * <p>模型客户端与超时统一走 {@link AiChatModelProvider}（不打印请求与响应：
 * 这里的提示词带工单描述与知识库内容，进去就是业务数据进了日志，AGENTS §5.8）。
 */
@Component
public class LangChainAdviceModel implements AdviceModel {

    private final AiChatModelProvider provider;

    public LangChainAdviceModel(AiChatModelProvider provider) {
        this.provider = provider;
    }

    @Override
    public String generate(String description, String categoryName, List<KnowledgeHit> hits) {
        return provider.call("维修建议", () -> provider.get()
                .chat(SystemMessage.from(KnowledgePrompts.system()),
                        UserMessage.from(KnowledgePrompts.userMessage(description, categoryName, hits)))
                .aiMessage().text());
    }
}
