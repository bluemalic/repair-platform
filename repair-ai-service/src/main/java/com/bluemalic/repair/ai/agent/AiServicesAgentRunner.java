package com.bluemalic.repair.ai.agent;

import com.bluemalic.repair.ai.AiChatModelProvider;
import com.bluemalic.repair.common.BizException;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LangChain4j {@code AiServices} 引擎（阶段 6 · ADR-037，ADR-031 的落地）。
 *
 * <p>原来那段"问模型 → 执行工具 → 回灌 → 再问"的手写循环交给框架，我们只保留三个控制点：
 * <ol>
 *   <li><b>工具白名单</b>：只把 {@link AgentToolRegistry} 里的工具放进 {@code tools(map)}。
 *       没进 map 的工具，模型即使编出名字也调不到——与手写循环里"find 返回 null 就拒绝"是同一个结果；</li>
 *   <li><b>步数上限</b>：{@code maxSequentialToolsInvocations(maxSteps)}（与
 *       {@code REPAIR_AGENT_MAX_STEPS} 同一个值），框架超限会中止本轮，这里转成 {@code truncated=true}；</li>
 *   <li><b>轨迹</b>：每个工具的执行都经过我们自己的 {@link ToolExecutor}，在那里计时并记
 *       {@link AgentTraceStep}——所以轨迹的内容与手写循环时代**完全一致**。</li>
 * </ol>
 *
 * <p><b>为什么每次调用新建一个 {@code ChatMemory}</b>：历史由调用方（{@code TicketAgentServiceImpl}）
 * 按会话组装好传进来，它已经是"system + 之前轮次"；用共享 memory 反而会把并发会话串在一起。
 * 窗口大小取"历史条数 + 1"，等价于不截断——截断策略在会话层（{@code AgentSessionStore}）。
 *
 * <p><b>模型异常照旧往上抛</b>：{@link AiChatModelProvider#call} 已经把"没配 Key / 模型不可用"
 * 翻译成 40004，业务层要靠它给用户明确提示；只有其它 RuntimeException（框架超限、序列化异常）
 * 才降级成 {@code truncated=true}。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "repair.agent", name = "engine",
        havingValue = "aiservices", matchIfMissing = true)
public class AiServicesAgentRunner implements AgentRunner {

    /** 轨迹里的结果摘要长度：轨迹是线索，不是结果本身（与手写循环的取值一致）。 */
    private static final int SUMMARY_MAX_CHARS = 60;

    private final AgentToolRegistry registry;

    private final AiChatModelProvider provider;

    private final int maxSteps;

    public AiServicesAgentRunner(AgentToolRegistry registry,
                                 AiChatModelProvider provider,
                                 @Value("${REPAIR_AGENT_MAX_STEPS:6}") int maxSteps) {
        this.registry = registry;
        this.provider = provider;
        this.maxSteps = Math.max(1, maxSteps);
    }

    @Override
    public AgentRun run(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("Agent 消息不能为空");
        }
        ChatMessage last = messages.get(messages.size() - 1);
        if (!(last instanceof UserMessage userMessage)) {
            throw new IllegalArgumentException("Agent 消息序列的最后一条必须是用户输入");
        }

        List<AgentTraceStep> trace = new ArrayList<>();
        // 窗口必须容得下**一轮工具调用产生的三条消息**（AiMessage(tool_calls) + ToolExecutionResult + AiMessage），
        // 否则第二轮工具调用时会把带 tool_calls 的那条挤掉，留下一个没有前驱的 tool 结果。
        // DeepSeek 会直接拒收：`Messages with role 'tool' must be a response to a preceding message with 'tool_calls'`。
        //
        // 这一条是 2026-11-11 真跑评测才暴露的：单元测试里的脚本化模型不校验消息序列，
        // 而窗口只给「历史条数」在单轮工具调用下刚好够，两轮就断——所以这里按 maxSteps 的两倍留足。
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(memoryCapacity(messages.size(), maxSteps));
        // system 与之前轮次先灌进去；最后一条用户输入交给 chat()，避免重复
        messages.subList(0, messages.size() - 1).forEach(memory::add);

        AgentAssistant assistant = AiServices.builder(AgentAssistant.class)
                .chatModel(provider.get())
                .chatMemory(memory)
                .tools(toolExecutors(trace))
                .maxSequentialToolsInvocations(maxSteps)
                .build();

        try {
            String answer = provider.call("Agent 编排", () -> assistant.chat(userMessage.singleText()));
            return new AgentRun(answer, trace, false);
        } catch (BizException e) {
            // 40004（模型不可用 / 没配 Key）：业务层要据此给出明确提示，不能吞
            throw e;
        } catch (RuntimeException e) {
            log.warn("Agent 编排中止（可能是工具调用超过上限 maxSteps={}），已调用 {} 次工具",
                    maxSteps, trace.size(), e);
            return new AgentRun("", trace, true);
        }
    }

    /**
     * 记忆窗口容量：历史 + 每条工具调用占 2 条（请求 + 结果）+ 收尾的助手消息 + 余量。
     *
     * <p>下限 20 是为了短会话（只有 system + 一句用户输入）时不至于贴着边界跑。
     * 窗口**只影响成本**（发多少条给模型），不影响正确性——截断由会话层（{@code AgentSessionStore}）负责，
     * 那里有明确的上限与淘汰策略。
     */
    static int memoryCapacity(int historySize, int maxSteps) {
        return Math.max(20, historySize + maxSteps * 2 + 4);
    }

    /**
     * 把白名单工具桥接成框架要的 {@code spec → executor} 映射。
     *
     * <p>用映射而不是"把工具对象直接传进去（{@code tools(Object...)}）"：
     * 后者的前提是工具方法上有 {@code @Tool} 注解，而我们的工具是 {@link AgentTool} 接口
     * （白名单、只读、不抛异常这些约束都在接口契约里）。桥接保留接口，也就不用把
     * 每个工具改写成注解方法——那会让"工具能干什么"从一处变成 N 处。
     */
    private Map<ToolSpecification, ToolExecutor> toolExecutors(List<AgentTraceStep> trace) {
        Map<ToolSpecification, ToolExecutor> map = new LinkedHashMap<>();
        for (AgentTool tool : registry.all()) {
            map.put(tool.spec(), (request, memoryId) -> {
                long start = System.nanoTime();
                String result = tool.execute(request.arguments());
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                trace.add(AgentTraceStep.ok(tool.name(), elapsedMs, summarize(result)));
                return result;
            });
        }
        return map;
    }

    private static String summarize(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= SUMMARY_MAX_CHARS ? oneLine : oneLine.substring(0, SUMMARY_MAX_CHARS);
    }
}
