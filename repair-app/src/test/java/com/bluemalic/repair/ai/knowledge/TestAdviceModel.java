package com.bluemalic.repair.ai.knowledge;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 测试作用域的假建议模型：顶替真的那个（{@code @Primary}），让维修建议链路能在集成测试里端到端跑，
 * 却既不调模型也不花钱——与 {@code TestAgentModel} / {@code TestAiAssistant} 同一个思路
 * （**不用 {@code @MockBean}**：它会多起一个 Spring 上下文，把 Sa-Token 的全局静态字段串掉）。
 *
 * <p>它顺便承担一个断言职责：{@link #callCount()} 让"检索零命中时**没有调模型**"这条能被测到——
 * 那是 ADR-018 里明确要省的钱，光看接口返回是看不出来的。
 */
@Primary
@Component
public class TestAdviceModel implements AdviceModel {

    private final Deque<String> script = new ArrayDeque<>();
    private int calls;

    /** 排一条模型的原始返回（通常是一段 JSON，可以故意带 markdown 围栏或非法引用）。 */
    public void willAnswer(String raw) {
        script.add(raw);
    }

    /** 每个用例开始前清空（Bean 是单例，状态会跨用例残留）。 */
    public void reset() {
        script.clear();
        calls = 0;
    }

    public int callCount() {
        return calls;
    }

    @Override
    public String generate(String description, String categoryName, List<KnowledgeHit> hits) {
        calls += 1;
        if (script.isEmpty()) {
            // 没排脚本就说明这个用例没打算走模型：让它立刻红，别安静地给出假答案
            throw new BizException(ErrorCode.AI_MODEL_UNAVAILABLE);
        }
        return script.poll();
    }
}
