package com.bluemalic.repair.ai.agent;

import com.bluemalic.repair.ai.AiChatModelProvider;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code AiServices} 引擎的三条契约（阶段 6 · ADR-037）。
 *
 * <p>这套编排换成了框架，但业务层依赖的三个行为不能变：**最终回答拿得到、工具执行留轨迹、
 * 消息序列的最后一条必须是用户输入**。用脚本化 {@code ChatModel} 测，不花钱、不碰网络。
 *
 * <p>刻意没有测"步数上限"：超限时的中止由框架内部实现（抛异常），行为随版本可能变；
 * 我们只保证"异常被转成 truncated=true 而不是把整轮对话炸掉"——那一条靠 catch 分支，
 * 真实验证要跑评测（docs/07 §5）。
 */
class AiServicesAgentRunnerTest {

    private final AgentToolRegistry registry = mock(AgentToolRegistry.class);

    private final AiChatModelProvider provider = mock(AiChatModelProvider.class);

    @Test
    void 模型直接回答时不执行任何工具() {
        when(registry.all()).thenReturn(List.of(tool("list_buildings", "3 栋楼")));
        stubModel(ai("宿舍有 1/2/3 号楼"));

        AgentRun run = runner().run(List.of(SystemMessage.from("你是受理助手"), UserMessage.from("有哪些楼")));

        assertThat(run.reply()).isEqualTo("宿舍有 1/2/3 号楼");
        assertThat(run.trace()).isEmpty();
        assertThat(run.stepLimitReached()).isFalse();
    }

    @Test
    void 工具调用会进轨迹并截断摘要() {
        String longResult = "很长的工具结果".repeat(20);
        when(registry.all()).thenReturn(List.of(tool("list_buildings", longResult)));
        stubModel(
                ai("", ToolExecutionRequest.builder()
                        .id("call-1").name("list_buildings").arguments("{}").build()),
                ai("共有 3 栋楼"));

        AgentRun run = runner().run(List.of(SystemMessage.from("你是受理助手"), UserMessage.from("有哪些楼")));

        assertThat(run.reply()).isEqualTo("共有 3 栋楼");
        assertThat(run.trace()).hasSize(1);
        AgentTraceStep step = run.trace().get(0);
        assertThat(step.tool()).isEqualTo("list_buildings");
        assertThat(step.rejected()).isFalse();
        assertThat(step.summary()).hasSizeLessThanOrEqualTo(60);
        assertThat(run.stepLimitReached()).isFalse();
    }

    /**
     * 回归：**两轮工具调用之后，消息序列仍然合法**。
     *
     * <p>2026-11-11 真跑评测时，Agent 第一次请求就 40004，DeepSeek 报
     * {@code Messages with role 'tool' must be a response to a preceding message with 'tool_calls'}。
     * 根因是记忆窗口只给了「历史条数」：第二轮工具调用时把带 {@code tool_calls} 的 AiMessage 挤掉，
     * 留下一个没有前驱的 tool 结果。单轮用例发现不了——它刚好贴着窗口边界但不越界。
     *
     * <p>这条断言与模型无关：直接检查框架发出去的第三次请求里，每个 {@code tool} 结果的前一条
     * 必须是带 {@code tool_calls} 的 assistant 消息。
     */
    /**
     * 窗口容量的**硬下限**：一条工具调用要在记忆里占 2 条消息（{@code AiMessage(tool_calls)} + 结果），
     * 所以「历史 + 2 × 步数」是能跑满 {@code maxSteps} 轮的最小值。
     *
     * <p>这条比下面那条更直接：下面的用例只能验"序列合法"，而真实故障是"窗口太小导致序列不合法"，
     * 脚本化模型不会像 DeepSeek 那样拒收，所以必须把窗口本身钉住。
     */
    @Test
    void 记忆窗口容得下跑满步数的工具往返() {
        assertThat(AiServicesAgentRunner.memoryCapacity(2, 6)).isGreaterThanOrEqualTo(2 + 6 * 2);
        assertThat(AiServicesAgentRunner.memoryCapacity(0, 1)).as("短会话也有下限，不贴边界跑").isGreaterThanOrEqualTo(20);
    }

    @Test
    void 两轮工具调用后消息序列仍然合法() {
        when(registry.all()).thenReturn(List.of(tool("list_buildings", "3 栋楼")));
        List<List<ChatMessage>> requests = new ArrayList<>();
        ChatModel model = new ChatModel() {
            private int round;

            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(new ArrayList<>(request.messages()));
                round++;
                if (round <= 2) {
                    return ChatResponse.builder().aiMessage(ai("", ToolExecutionRequest.builder()
                            .id("call-" + round).name("list_buildings").arguments("{}").build())).build();
                }
                return ChatResponse.builder().aiMessage(ai("共有 3 栋楼")).build();
            }
        };
        when(provider.get()).thenReturn(model);
        when(provider.call(anyString(), any())).thenAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(1);
            return action.get();
        });

        AgentRun run = runner().run(List.of(SystemMessage.from("你是受理助手"), UserMessage.from("有哪些楼")));

        assertThat(run.reply()).isEqualTo("共有 3 栋楼");
        assertThat(run.trace()).hasSize(2);
        List<ChatMessage> third = requests.get(2);
        for (int i = 0; i < third.size(); i++) {
            if (third.get(i) instanceof dev.langchain4j.data.message.ToolExecutionResultMessage) {
                assertThat(third.get(i - 1))
                        .as("tool 结果的前一条必须是带 tool_calls 的 assistant 消息（否则模型拒收）")
                        .isInstanceOf(AiMessage.class);
                assertThat(((AiMessage) third.get(i - 1)).hasToolExecutionRequests()).isTrue();
            }
        }
    }

    @Test
    void 最后一条不是用户输入时直接拒绝() {
        assertThatThrownBy(() -> runner().run(List.of(SystemMessage.from("你是受理助手"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户输入");
    }

    private AiServicesAgentRunner runner() {
        return new AiServicesAgentRunner(registry, provider, 6);
    }

    /** provider.call 只是包一层错误翻译，测试里直接执行；provider.get 返回脚本化模型。 */
    private void stubModel(AiMessage... replies) {
        ChatModel model = scripted(replies);
        when(provider.get()).thenReturn(model);
        when(provider.call(anyString(), any())).thenAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(1);
            return action.get();
        });
    }

    private static ChatModel scripted(AiMessage... replies) {
        AtomicInteger cursor = new AtomicInteger();
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                int index = Math.min(cursor.getAndIncrement(), replies.length - 1);
                return ChatResponse.builder().aiMessage(replies[index]).build();
            }
        };
    }

    private static AiMessage ai(String text, ToolExecutionRequest... requests) {
        return requests.length == 0
                ? AiMessage.from(text)
                : AiMessage.from(text, List.of(requests));
    }

    private static AgentTool tool(String name, String result) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "测试工具";
            }

            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder().name(name).description(description()).build();
            }

            @Override
            public String execute(String argumentsJson) {
                return result;
            }
        };
    }

    /** 保留给将来"多轮历史"用例：当前用例都只传一轮。 */
    @SuppressWarnings("unused")
    private static List<ChatMessage> history(String... lines) {
        List<ChatMessage> messages = new ArrayList<>();
        for (String line : lines) {
            messages.add(UserMessage.from(line));
        }
        return messages;
    }
}
