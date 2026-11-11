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
