package com.bluemalic.repair.ai.agent;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 跑一轮 Agent 的入口（阶段 6 · ADR-037）。
 *
 * <p>它只有一个方法，但存在的意义是**把"怎么编排"与"调 Agent 的业务代码"解耦**：
 * 业务层（受理 / 分诊）只关心"给一段历史、拿一个结果 + 轨迹"，
 * 至于这一轮是手写循环还是 LangChain4j 的 {@code AiServices} 跑的，由配置
 * {@code repair.agent.engine=aiservices|loop} 决定，业务代码不用改。
 *
 * <p>两种实现的行为契约必须一致：
 * <ul>
 *   <li>工具只从 {@link AgentToolRegistry} 的白名单里取，未知工具**不执行**；</li>
 *   <li>最多 {@code REPAIR_AGENT_MAX_STEPS} 次工具调用，超了返回 {@code truncated=true}；</li>
 *   <li>每一次工具调用都进 {@link AgentTraceStep}（工具名 / 耗时 / 结果摘要），用户原文不进轨迹。</li>
 * </ul>
 */
public interface AgentRunner {

    /**
     * @param messages 完整消息序列：system + 之前轮次 + **最后一条是这一轮的用户输入**
     * @return 最终回答 + 轨迹 + 是否触发了步数上限
     */
    AgentRun run(List<ChatMessage> messages);
}
