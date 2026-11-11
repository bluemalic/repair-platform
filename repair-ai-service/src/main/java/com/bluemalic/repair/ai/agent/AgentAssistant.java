package com.bluemalic.repair.ai.agent;

import dev.langchain4j.service.UserMessage;

/**
 * {@code AiServices} 的代理接口（阶段 6 · ADR-037）。
 *
 * <p>只声明一个方法：**编排细节全部由框架负责**（发请求、拿工具调用、执行、回灌、循环），
 * 我们只保留三个控制点：工具清单（构造时只传白名单）、步数上限
 * （{@code maxSequentialToolsInvocations}）、轨迹（在 {@code ToolExecutor} 里记）。
 *
 * <p>system 提示词不写在这里：它在 {@link com.bluemalic.repair.ai.agent.AiServicesAgentRunner}
 * 灌进 {@code ChatMemory} 的首条消息里——这样"每轮换提示词"与"提示词来自调用方"这两件事保持原样，
 * 不把提示词钉死在注解上。
 */
public interface AgentAssistant {

    String chat(@UserMessage String userMessage);
}
