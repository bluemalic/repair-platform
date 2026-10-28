package com.bluemalic.repair.ai.agent;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import dev.langchain4j.data.message.ChatMessage;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 测试作用域的假模型：**顶替真的那个**（{@code @Primary}），让 Agent 链路能在集成测试里端到端跑，
 * 却既不调模型也不花钱。
 *
 * <p>为什么用 {@code @Primary} 的测试 Bean 而不是 {@code @MockBean}：{@code @MockBean} 会改测试
 * 上下文的缓存键，让 Spring 再起一个上下文，而 Sa-Token 的 {@code StpInterface} 是按上下文启动顺序
 * 写全局静态字段的——多上下文会串（{@code IntegrationTest} 的类注释记着这个坑，项目为此烧过 9 次红 CI）。
 * 这个类放在 {@code src/test/java} 的同一根包下，会被组件扫描扫到，于是**一个上下文都不多起**。
 * 与 {@code TestAiAssistant} 同一个思路。
 *
 * <p>默认行为是**抛"模型不可用"**：哪个用例不小心碰到 AI，会立刻红，而不是安静地拿到一个假回答。
 * 需要走通链路的用例自己 {@link #willAnswer} / {@link #willCallTool} 排脚本。
 */
@Primary
@Component
public class TestAgentModel implements AgentModel {

    private final Deque<AgentStep> script = new ArrayDeque<>();
    private List<ChatMessage> lastMessages = List.of();
    private int callSeq;

    /** 排一步"直接回答"（最终回答，通常是一段 JSON 或一句追问）。 */
    public void willAnswer(String text) {
        script.add(new AgentStep(text, List.of()));
    }

    /** 排一步"调用工具"（工具名必须是白名单里的，否则正好用来测拒绝分支）。 */
    public void willCallTool(String toolName, String argumentsJson) {
        script.add(new AgentStep("", List.of(
                new AgentToolCall("call-" + (++callSeq), toolName, argumentsJson))));
    }

    /** 最近一次收到的消息（断言"位置上下文确实传给了模型"用）。 */
    public List<ChatMessage> lastMessages() {
        return lastMessages;
    }

    /** 每个用例开始前清空脚本（Bean 是单例，状态会跨用例残留）。 */
    public void reset() {
        script.clear();
        lastMessages = List.of();
        callSeq = 0;
    }

    @Override
    public AgentStep next(List<ChatMessage> messages) {
        lastMessages = List.copyOf(messages);
        if (script.isEmpty()) {
            // 没排脚本就说明这个用例没打算走 AI——让它立刻红，别安静地给出假答案
            throw new BizException(ErrorCode.AI_MODEL_UNAVAILABLE);
        }
        return script.poll();
    }
}
