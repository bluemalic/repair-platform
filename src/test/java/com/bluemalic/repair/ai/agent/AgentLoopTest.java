package com.bluemalic.repair.ai.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编排循环的行为测试——**全部用脚本化的假模型驱动，不联网、不花钱**。
 *
 * <p>这正是 ADR-017 把"循环依赖 {@link AgentModel} 接口"的收益：步数上限、工具白名单、
 * 未知工具的处理、工具抛异常时的兜底、轨迹记录，这些最容易写错的地方都能在这里钉死。
 * 真模型那层只剩"组装 ChatRequest / 解析 AiMessage"，薄到靠一次真实调用验证即可。
 */
class AgentLoopTest {

    /** 按预先写好的脚本返回步骤的假模型。 */
    private static final class ScriptedModel implements AgentModel {

        private final Deque<AgentStep> script = new ArrayDeque<>();
        int calls;

        ScriptedModel(AgentStep... steps) {
            script.addAll(List.of(steps));
        }

        @Override
        public AgentStep next(List<ChatMessage> messages) {
            calls++;
            return script.isEmpty() ? new AgentStep("（脚本用尽）", List.of()) : script.poll();
        }
    }

    /** 记录被调用情况的假工具。 */
    private static final class FakeTool implements AgentTool {

        private final String name;
        private final String result;
        private final boolean fail;
        int calls;

        FakeTool(String name, String result) {
            this(name, result, false);
        }

        FakeTool(String name, String result, boolean fail) {
            this.name = name;
            this.result = result;
            this.fail = fail;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "测试用工具";
        }

        @Override
        public ToolSpecification spec() {
            return ToolSpecification.builder().name(name).description(description())
                    .parameters(JsonObjectSchema.builder().build()).build();
        }

        @Override
        public String execute(String argumentsJson) {
            calls++;
            if (fail) {
                throw new IllegalStateException("模拟工具内部出错");
            }
            return result;
        }
    }

    private AgentLoop loopOf(AgentTool... tools) {
        // 步数上限传 3：测试里要能容易地走到"用尽"这条分支
        return new AgentLoop(new AgentToolRegistry(List.of(tools)), 3);
    }

    private List<ChatMessage> history() {
        return new ArrayList<>(List.of(UserMessage.from("1 号楼水管漏了")));
    }

    @Test
    void 不需要工具时一步就结束() {
        ScriptedModel model = new ScriptedModel(new AgentStep("请问是哪个房间？", List.of()));

        AgentRun run = loopOf().run(model, history());

        assertThat(run.reply()).isEqualTo("请问是哪个房间？");
        assertThat(run.trace()).isEmpty();
        assertThat(run.stepLimitReached()).isFalse();
        assertThat(model.calls).isEqualTo(1);
    }

    @Test
    void 调工具后把结果回灌给模型再拿最终回答() {
        FakeTool tool = new FakeTool("listBuildings", "[{\"id\":1,\"name\":\"1 号楼\"}]");
        ScriptedModel model = new ScriptedModel(
                new AgentStep("", List.of(new AgentToolCall("c1", "listBuildings", "{\"keyword\":\"1 号\"}"))),
                new AgentStep("已确认是 1 号楼，请问房间号？", List.of()));

        AgentRun run = loopOf(tool).run(model, history());

        assertThat(tool.calls).isEqualTo(1);
        assertThat(run.reply()).contains("房间号");
        assertThat(run.trace()).hasSize(1);
        assertThat(run.trace().get(0).tool()).isEqualTo("listBuildings");
        assertThat(run.trace().get(0).rejected()).isFalse();
        assertThat(run.trace().get(0).summary()).contains("1 号楼");
        assertThat(model.calls).isEqualTo(2);
    }

    /**
     * 白名单的实质意义就在这里：模型**要求**调一个不存在的工具时，循环不执行、不抛错，
     * 而是把"该工具不可用"作为工具结果回灌——模型据此改用别的工具或直接问用户。
     */
    @Test
    void 白名单外的工具被拒绝且不执行() {
        FakeTool allowed = new FakeTool("listBuildings", "[]");
        ScriptedModel model = new ScriptedModel(
                new AgentStep("", List.of(new AgentToolCall("c1", "queryRepairCode", "{\"code\":\"123456\"}"))),
                new AgentStep("我无法按报修码查询，请把楼栋与房间告诉我。", List.of()));

        AgentRun run = loopOf(allowed).run(model, history());

        assertThat(allowed.calls).isZero();
        assertThat(run.trace()).hasSize(1);
        assertThat(run.trace().get(0).rejected()).isTrue();
        assertThat(run.trace().get(0).tool()).isEqualTo("queryRepairCode");
        assertThat(run.reply()).contains("请把楼栋");
    }

    /** 工具里的 bug 不该把整轮对话带走：循环兜住异常，回一句话给模型继续。 */
    @Test
    void 工具抛异常时兜住并继续() {
        FakeTool broken = new FakeTool("listBuildings", "", true);
        ScriptedModel model = new ScriptedModel(
                new AgentStep("", List.of(new AgentToolCall("c1", "listBuildings", "{}"))),
                new AgentStep("我先记下你的描述，稍后由后勤确认楼栋。", List.of()));

        AgentRun run = loopOf(broken).run(model, history());

        assertThat(broken.calls).isEqualTo(1);
        assertThat(run.reply()).contains("后勤确认");
        assertThat(run.trace()).hasSize(1);
    }

    /** 步数用尽是"正常的收尾"而不是异常：交回已有状态，由调用方追问。 */
    @Test
    void 到达步数上限后收尾并标记() {
        FakeTool tool = new FakeTool("listCategories", "[]");
        ScriptedModel model = new ScriptedModel(
                new AgentStep("", List.of(new AgentToolCall("c1", "listCategories", "{}"))),
                new AgentStep("", List.of(new AgentToolCall("c2", "listCategories", "{}"))),
                new AgentStep("", List.of(new AgentToolCall("c3", "listCategories", "{}"))),
                new AgentStep("这一步不该被走到", List.of()));

        AgentRun run = loopOf(tool).run(model, history());

        assertThat(run.stepLimitReached()).isTrue();
        assertThat(tool.calls).isEqualTo(3);
        assertThat(model.calls).isEqualTo(3);
        assertThat(run.trace()).hasSize(3);
    }

    @Test
    void 同名工具在注册时就被拒绝() {
        FakeTool a = new FakeTool("dup", "1");
        FakeTool b = new FakeTool("dup", "2");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AgentToolRegistry(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重名");
    }
}
