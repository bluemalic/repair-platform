package com.bluemalic.repair.ai;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 测试作用域的假模型：**顶替真的那个**（{@code @Primary}），让问数链路能在 CI 上端到端跑，
 * 却既不调模型也不花钱。
 *
 * <p><b>为什么用 {@code @Primary} 的测试 Bean，而不是 {@code @MockBean}</b>：{@code @MockBean}
 * 会改测试上下文的缓存键，让 Spring 再起一个上下文，而 Sa-Token 的 {@code StpInterface} 是按
 * 上下文启动顺序写全局静态字段的——多上下文会串（{@code IntegrationTest} 的类注释里记了这个坑）。
 * 这个类放在 {@code src/test/java} 的同一个根包下，会被组件扫描扫到，于是**一个上下文都不多起**。
 *
 * <p>默认行为是**抛"模型不可用"**：这样哪个用例不小心碰到 AI，会立刻红，而不是安静地拿到一个假答案。
 * 需要走通链路的用例自己 {@link #willReturn} 设一条计划。
 */
@Primary
@Component
public class TestAiAssistant implements AiAssistant {

    private QueryPlan plan;
    private String conclusion = "（测试用结论）";
    private AiQueryResult summarizedRows;

    /** 让下一次{@code plan} 返回这条计划。 */
    public void willReturn(QueryPlan plan) {
        this.plan = plan;
    }

    /** 让下一次{@code summarize} 返回这句结论。 */
    public void willConclude(String conclusion) {
        this.conclusion = conclusion;
    }

    /** 结论那一步收到的数据（断言"模型确实看到了真实结果"用）。 */
    public AiQueryResult summarizedRows() {
        return summarizedRows;
    }

    @Override
    public QueryPlan plan(String question) {
        if (plan == null) {
            throw new BizException(ErrorCode.AI_MODEL_UNAVAILABLE, "测试里没有设定模型回复");
        }
        return plan;
    }

    @Override
    public String summarize(String question, AiQueryResult result) {
        this.summarizedRows = result;
        return conclusion;
    }
}
