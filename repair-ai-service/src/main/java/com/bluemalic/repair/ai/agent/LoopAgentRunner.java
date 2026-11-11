package com.bluemalic.repair.ai.agent;

import dev.langchain4j.data.message.ChatMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 手写循环引擎（ADR-017 的原实现），保留一个发布周期作为回退（阶段 6 · ADR-037）。
 *
 * <p>开关：{@code repair.agent.engine=loop}。默认走 {@link AiServicesAgentRunner}——
 * 那套编排与我们自己的循环在"白名单 / 步数上限 / 轨迹"上必须表现一致，
 * 所以两套都留着，出问题时一行配置就能切回去，而不用回滚整个版本。
 */
@Component
@ConditionalOnProperty(prefix = "repair.agent", name = "engine", havingValue = "loop")
public class LoopAgentRunner implements AgentRunner {

    private final AgentLoop agentLoop;

    private final AgentModel agentModel;

    public LoopAgentRunner(AgentLoop agentLoop, AgentModel agentModel) {
        this.agentLoop = agentLoop;
        this.agentModel = agentModel;
    }

    @Override
    public AgentRun run(List<ChatMessage> messages) {
        return agentLoop.run(agentModel, messages);
    }
}
