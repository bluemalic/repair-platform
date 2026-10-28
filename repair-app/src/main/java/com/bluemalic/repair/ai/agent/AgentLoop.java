package com.bluemalic.repair.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 手写编排循环（ADR-017）：**问模型 → 执行它要的工具 → 把结果回灌 → 再问**，直到它给出最终回答
 * 或用完步数。
 *
 * <p>为什么手写而不是用 LangChain4j 的 {@code AiServices}：这个循环要能自己控制四件事——
 * <b>步数上限、工具白名单、人工确认点、轨迹记录</b>。交给框架就等于把这四件放进黑盒，
 * 而它们恰好是最需要"读代码就能确认"的部分。循环本身只有几十行。
 *
 * <p><b>未知工具不执行、不重试</b>：把"该工具不可用"作为工具结果回灌，让模型自己改用别的工具
 * 或直接回答。这比抛异常更稳——模型对"工具返回了一段解释"是有能力处理的。
 *
 * <p>依赖 {@link AgentModel} 而不是直接依赖 ChatModel：于是测试可以塞一个脚本化的假实现，
 * 把步数上限、白名单、轨迹这些行为全部测掉，不花钱也不碰网络。
 */
@Slf4j
@Component
public class AgentLoop {

    /** 留给模型"结果摘要"的字数：轨迹是给人看的线索，不是结果本身。 */
    private static final int SUMMARY_MAX_CHARS = 60;

    private final AgentToolRegistry registry;
    private final int maxSteps;

    public AgentLoop(AgentToolRegistry registry,
                     @Value("${REPAIR_AGENT_MAX_STEPS:6}") int maxSteps) {
        this.registry = registry;
        this.maxSteps = Math.max(1, maxSteps);
    }

    /**
     * 跑一轮。
     *
     * @param model  模型（真实现或测试用的假实现）
     * @param history 完整消息历史，**含 system 提示词**，由调用方按当前 Agent 的场景组装
     */
    public AgentRun run(AgentModel model, List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>(history);
        List<AgentTraceStep> trace = new ArrayList<>();
        String lastText = "";

        for (int step = 1; step <= maxSteps; step++) {
            AgentStep stepResult = model.next(messages);
            if (!stepResult.text().isBlank()) {
                lastText = stepResult.text();
            }
            if (stepResult.isFinal()) {
                return new AgentRun(lastText, trace, false);
            }

            // 模型这一步（含工具请求）必须先入历史：工具结果要有一条对应的"请求"才能被模型接受。
            // 少了这条，多数模型会直接报"消息序列非法"。
            List<ToolExecutionRequest> requests = stepResult.toolCalls().stream()
                    .map(c -> ToolExecutionRequest.builder()
                            .id(c.id())
                            .name(c.name())
                            .arguments(c.argumentsJson())
                            .build())
                    .toList();
            messages.add(AiMessage.from(stepResult.text(), requests));

            for (ToolExecutionRequest request : requests) {
                messages.add(executeTool(request, trace));
            }
        }

        log.warn("Agent 达到步数上限 maxSteps={} 工具调用次数={}", maxSteps, trace.size());
        return new AgentRun(lastText, trace, true);
    }

    /** 执行一个工具调用并转成回灌消息。**任何失败都变成一句话**，不抛（理由见类注释）。 */
    private ToolExecutionResultMessage executeTool(ToolExecutionRequest request, List<AgentTraceStep> trace) {
        AgentTool tool = registry.find(request.name());
        if (tool == null) {
            log.warn("Agent 调用了白名单外的工具 name={}", request.name());
            trace.add(AgentTraceStep.rejected(request.name(), "不在白名单内，已拒绝"));
            return ToolExecutionResultMessage.from(request, "该工具不可用：" + request.name());
        }
        long start = System.nanoTime();
        String result;
        try {
            result = tool.execute(request.arguments());
        } catch (Exception e) {
            // AgentTool 的契约是"不抛异常"，但契约靠自觉不如兜一层：
            // 工具里的一个 bug 不该把整轮对话带走（用户会觉得"AI 坏了"，而其实只是某个查询出错）
            log.warn("工具 {} 抛出异常 err={}", request.name(), e.getMessage());
            result = "工具内部出错。请改用其他方式，或直接向用户询问。";
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        trace.add(AgentTraceStep.ok(request.name(), ms, summarize(result)));
        return ToolExecutionResultMessage.from(request, result);
    }

    private String summarize(String result) {
        String flat = result == null ? "" : result.replace('\n', ' ').trim();
        return flat.length() <= SUMMARY_MAX_CHARS ? flat : flat.substring(0, SUMMARY_MAX_CHARS) + "…";
    }
}
