package com.bluemalic.repair.ai.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具白名单。**注入哪些 {@link AgentTool} Bean，模型就能用哪些工具**——没有"运行时注册"这条路，
 * 于是"Agent 能干什么"在启动时就定死了，读代码就能确认（ADR-017）。
 *
 * <p>重名在启动时直接失败：模型只能按名字调用，重名意味着"调的是谁不确定"，
 * 那属于配置事故，不该等到运行时才发现。
 */
@Component
public class AgentToolRegistry {

    private final Map<String, AgentTool> tools;

    public AgentToolRegistry(List<AgentTool> beans) {
        Map<String, AgentTool> map = new LinkedHashMap<>();
        for (AgentTool tool : beans) {
            AgentTool previous = map.put(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException(
                        "Agent 工具重名：" + tool.name() + "（" + previous.getClass().getName()
                                + " 与 " + tool.getClass().getName() + "）");
            }
        }
        this.tools = Map.copyOf(map);
    }

    /** 交给模型的工具清单。 */
    public List<ToolSpecification> specs() {
        return tools.values().stream().map(AgentTool::spec).toList();
    }

    /** 按名字取工具；不在白名单里返回 {@code null}（调用方负责拒绝并回一句话给模型）。 */
    public AgentTool find(String name) {
        return tools.get(name);
    }

    /** 白名单里的工具名，供提示词与日志使用。 */
    public List<String> names() {
        return List.copyOf(tools.keySet());
    }
}
