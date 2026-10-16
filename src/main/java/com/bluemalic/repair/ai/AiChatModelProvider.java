package com.bluemalic.repair.ai;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.config.AiProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 全项目**唯一**创建 {@link ChatModel} 的地方。
 *
 * <p>抽出来是因为问数与 Agent 两条链路都要用它：模型名、baseUrl、超时、temperature、
 * 以及"不打印请求与响应"这些设置如果各写一份，迟早会漂移——而漂移的后果是
 * "两条链路对同一个模型的行为不一样"，或者更糟：**某一条把带业务数据的提示词打进了日志**
 * （AGENTS §5.8 明令不许）。
 *
 * <p>懒建 + 双检锁：没配 AI 的环境（本地跑大部分测试）不该因为"少一个 Key"而起不来，
 * 真正调用时才报 {@code AI_MODEL_UNAVAILABLE}。
 */
@Slf4j
@Component
public class AiChatModelProvider {

    /** 单次模型调用超时。比 SQL 的 3 秒宽松得多——生成 SQL 与写结论都要出整段文本。 */
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(60);

    private final AiProperties aiProperties;
    private volatile ChatModel chatModel;

    public AiChatModelProvider(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    public ChatModel get() {
        ChatModel model = chatModel;
        if (model == null) {
            synchronized (this) {
                if (chatModel == null) {
                    if (!aiProperties.modelConfigured()) {
                        throw new BizException(ErrorCode.AI_MODEL_UNAVAILABLE,
                                "AI 未启用（服务器上没配 AI_MODEL_API_KEY）");
                    }
                    chatModel = OpenAiChatModel.builder()
                            .apiKey(aiProperties.getApiKey())
                            // DeepSeek 没有专用模块，官方推荐就是 open-ai 模块 + 自定义 baseUrl（ADR-005）
                            .baseUrl(aiProperties.getBaseUrl())
                            .modelName(aiProperties.getModelName())
                            .timeout(MODEL_TIMEOUT)
                            // 要的是确定性与可复现：同一个问题应给出同一个查询 / 同一个草稿
                            .temperature(0.0)
                            // **不要打开**：提示词里带表结构（schema），回复里带业务数据，
                            // 打进日志就等于把库里的东西抄进了日志文件（AGENTS §5.8）
                            .logRequests(false)
                            .logResponses(false)
                            .build();
                }
                model = chatModel;
            }
        }
        return model;
    }

    /**
     * 把"调用模型"这一件事的异常口径收敛在一处：网络、鉴权、限流、超时对用户来说都是
     * "模型这会儿用不了"，统一成 {@code AI_MODEL_UNAVAILABLE}，并把真实原因留在 WARN 日志里。
     */
    public <T> T call(String what, Supplier<T> action) {
        try {
            return action.get();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("调用模型失败（{}）model={} err={}", what, aiProperties.getModelName(), e.getMessage());
            throw new BizException(ErrorCode.AI_MODEL_UNAVAILABLE);
        }
    }
}
