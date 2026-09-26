package com.bluemalic.repair.ai;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.config.AiProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型层"没配 Key 就别往下走"这条前置检查（纯单测，不起 Spring、不连库、不调模型）。
 *
 * <p>协作者故意传 {@code null}：这条分支在用到它们**之前**就该返回（拼提示词要先读表结构、
 * 而"没配 Key"这个原因更靠前也更好修）。如果哪天有人把检查挪到后面，这个用例会因为
 * NullPointerException 而红——正是想要的效果。
 */
class LangChainAiAssistantTest {

    @Test
    void withoutApiKeyFailsBeforeTouchingAnythingElse() {
        AiProperties notConfigured = new AiProperties("", "http://localhost", "test-model",
                "ro", "ro-password", 3000, 1000, 10);
        LangChainAiAssistant assistant = new LangChainAiAssistant(notConfigured, null, null);

        assertThatThrownBy(() -> assistant.plan("有几栋楼"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("AI_MODEL_API_KEY")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_MODEL_UNAVAILABLE);

        assertThatThrownBy(() -> assistant.summarize("有几栋楼",
                new AiQueryResult(java.util.List.of("n"), java.util.List.of(), false)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_MODEL_UNAVAILABLE);
    }

    @Test
    void queryTimeoutIsConvertedToSecondsForJdbc() {
        AiProperties properties = new AiProperties("key", "http://localhost", "m",
                "ro", "pw", 3000, 1000, 10);
        assertThat(properties.queryTimeoutSeconds()).isEqualTo(3);

        // JDBC 的 setQueryTimeout 只认秒，0 表示"不限制"——那正是最不该出现的情况，所以向下取 1
        AiProperties tooSmall = new AiProperties("key", "http://localhost", "m",
                "ro", "pw", 200, 1000, 10);
        assertThat(tooSmall.queryTimeoutSeconds()).isEqualTo(1);
    }
}
