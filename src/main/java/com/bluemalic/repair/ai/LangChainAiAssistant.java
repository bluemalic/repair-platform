package com.bluemalic.repair.ai;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 真正调模型的那一层（LangChain4j + DeepSeek，ADR-005）。**这个类里没有任何安全逻辑**——
 * 它只负责"把提示词发出去、把回复解析回来"，安全由 {@link SqlSafetyGateway} 在代码里管。
 *
 * <p><b>为什么不用 LangChain4j 的 {@code AiServices} 注解式接口</b>：系统提示词是按（从库里读出来的）
 * 表结构动态拼的，而注解的值只能是编译期常量——硬套就得把提示词绕成 {@code {{变量}}} 模板传参，
 * 比直接发两条消息更难读。所以这里用最朴素的 {@code ChatModel.chat(SystemMessage, UserMessage)}：
 * 少一层需要理解的黑箱，提示词也就在上面那个类里一眼可见。
 *
 * <p><b>模型客户端是懒建且不参与启动</b>：没配 Key 时这个 Bean 什么都不做，直到第一次问数才报
 * {@code 40004}——与"应用启动不依赖 MinIO"同一条原则（少一个外部依赖，启动就少一个失败面）。
 *
 * <p>两个方法对应两次调用：{@link #plan} 生成 SQL，{@link #summarize} 在看到**真实结果**之后写结论。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LangChainAiAssistant implements AiAssistant {

    /** 模型侧超时：比数据库那 3 秒宽得多——生成 SQL 本来就要几秒，这是"等模型"不是"等查询"。 */
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(30);

    /** 给结论看的数据量上限：结论是一两句话，不需要把上千行都塞进提示词（那既费 token 也没用）。 */
    private static final int MAX_ROWS_FOR_CONCLUSION = 50;

    private final AiProperties aiProperties;

    private final SchemaPromptBuilder prompts;

    private final ObjectMapper objectMapper;

    private volatile ChatModel chatModel;

    @Override
    public QueryPlan plan(String question) {
        requireModelConfigured();
        String reply = chat(aiProperties.getModelName(), prompts.systemPrompt(), prompts.userMessage(question),
                "生成 SQL");
        QueryPlan plan = parsePlan(reply);
        if (plan.sql() == null || plan.sql().isBlank()) {
            // 模型没答上来（而不是不可用）：属于"这个问题没能解析成查询"
            log.warn("模型没有生成 SQL，回复前 200 字：{}", head(reply));
            throw new BizException(ErrorCode.AI_INTENT_UNRESOLVED);
        }
        return plan;
    }

    @Override
    public String summarize(String question, AiQueryResult result) {
        requireModelConfigured();
        AiQueryResult trimmed = result.rows().size() > MAX_ROWS_FOR_CONCLUSION
                ? new AiQueryResult(result.columns(),
                        List.copyOf(result.rows().subList(0, MAX_ROWS_FOR_CONCLUSION)), true)
                : result;
        String rowsJson = toJson(trimmed);
        String reply = chat(aiProperties.getModelName(), prompts.conclusionSystemPrompt(),
                prompts.conclusionUserMessage(question, rowsJson, trimmed.rowLimited()), "生成结论");
        return reply.trim();
    }

    /**
     * 没配 Key 就别往下走了。
     *
     * <p>放在最前面是有意的：拼提示词要先读数据库里的表结构，而"没配 Key"这个原因更靠前、
     * 也更好修——先报它，用户才不会被一个数据库错误引到错误的方向上。
     */
    private void requireModelConfigured() {
        if (!aiProperties.modelConfigured()) {
            throw new BizException(ErrorCode.AI_MODEL_UNAVAILABLE, "AI 问数未启用（服务器上没配 AI_MODEL_API_KEY）");
        }
    }

    private String chat(String modelName, String systemPrompt, String userMessage, String what) {
        try {
            return chatModel().chat(SystemMessage.from(systemPrompt), UserMessage.from(userMessage))
                    .aiMessage().text();
        } catch (Exception e) {
            // 网络、鉴权（Key 不对）、限流、超时都会走到这里。对用户来说都是"模型这会儿用不了"
            log.warn("调用模型失败（{}）model={} err={}", what, modelName, e.getMessage());
            throw new BizException(ErrorCode.AI_MODEL_UNAVAILABLE);
        }
    }

    /**
     * 解析模型的 JSON 回复。**解析失败就报错，不重试**：重试等于为一个问题再花一次钱，
     * 而这属于"模型没照格式答"，让用户再问一次更便宜也更可控。
     */
    private QueryPlan parsePlan(String reply) {
        String json = stripCodeFence(reply);
        try {
            return objectMapper.readValue(json, QueryPlan.class);
        } catch (Exception e) {
            log.warn("模型回复不是合法 JSON，前 200 字：{} err={}", head(reply), e.getMessage());
            throw new BizException(ErrorCode.AI_INTENT_UNRESOLVED);
        }
    }

    private ChatModel chatModel() {
        ChatModel model = chatModel;
        if (model == null) {
            synchronized (this) {
                if (chatModel == null) {
                    if (!aiProperties.modelConfigured()) {
                        throw new BizException(ErrorCode.AI_MODEL_UNAVAILABLE,
                                "AI 问数未启用（服务器上没配 AI_MODEL_API_KEY）");
                    }
                    chatModel = OpenAiChatModel.builder()
                            .apiKey(aiProperties.getApiKey())
                            // DeepSeek 没有专用模块，官方推荐就是 open-ai 模块 + 自定义 baseUrl（ADR-005）
                            .baseUrl(aiProperties.getBaseUrl())
                            .modelName(aiProperties.getModelName())
                            .timeout(MODEL_TIMEOUT)
                            // 问数要的是确定性与可复现：同一个问题应给出同一个查询
                            .temperature(0.0)
                            // **不要打开**：提示词里带表结构（schema），回复里带业务数据，
                            // 打进日志就等于把库里的东西抄进了日志文件（AGENTS §5.8）
                            .logRequests(false)
                            .logResponses(false)
                            .build();
                    model = chatModel;
                } else {
                    model = chatModel;
                }
            }
        }
        return model;
    }

    /** 模型常把 JSON 包在 markdown 代码围栏里，剥掉再解析。 */
    private String stripCodeFence(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd > 0) {
                text = text.substring(firstLineEnd + 1).trim();
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).trim();
            }
        }
        return text;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // 自己序列化自己产出的对象，真失败也只能是系统问题
            log.warn("序列化查询结果失败 err={}", e.getMessage());
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
    }

    private String head(String text) {
        if (text == null) {
            return "(null)";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
