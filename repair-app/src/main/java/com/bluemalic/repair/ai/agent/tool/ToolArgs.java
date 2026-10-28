package com.bluemalic.repair.ai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工具入参的**防御性**解析。
 *
 * <p>参数 JSON 是模型生成的，属不可信输入：模型偶尔漏引号、把布尔写成字符串、
 * 甚至整段加 markdown 围栏。所以这里的原则是 <b>解析失败一律返回缺省值，绝不抛异常</b>——
 * 抛一次就把整轮对话打断了，而"参数没听清"完全可以让模型再试一次或直接问用户。
 */
public final class ToolArgs {

    private ToolArgs() {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 取字符串参数；缺失 / JSON 非法 / 类型不符都返回 {@code null}。 */
    public static String stringArg(String argumentsJson, String key) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(argumentsJson);
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                return null;
            }
            String text = value.asText();
            return text == null || text.isBlank() ? null : text;
        } catch (Exception e) {
            return null;
        }
    }
}
