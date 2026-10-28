package com.bluemalic.repair.ai.agent;

/**
 * 模型要求调用的一次工具。
 *
 * <p>{@code id} 是模型给的调用标识，回填 {@code ToolExecutionResultMessage} 时要带上它——
 * 少了它，多工具并行调用的结果就分不清谁是谁。
 *
 * <p>{@code argumentsJson} 是模型按工具的参数 schema 生成的 JSON。**它不可信**：
 * 解析失败属于正常情况（模型偶尔会漏引号），由工具实现自己兜住并返回一句人话，
 * 而不是抛异常把整轮对话打断。
 */
public record AgentToolCall(String id, String name, String argumentsJson) {
}
