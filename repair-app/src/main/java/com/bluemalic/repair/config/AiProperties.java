package com.bluemalic.repair.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * AI 数据助手的配置：模型凭据、只读数据库账号、查询阈值。
 *
 * <ul>
 *   <li>{@code AI_MODEL_API_KEY} / {@code AI_MODEL_BASE_URL} / {@code AI_MODEL_NAME}：模型（已有，见 .env.example）</li>
 *   <li>{@code AI_DB_USERNAME} / {@code AI_DB_PASSWORD}：**只读账号**（ADR-003 第 1 层）。留空 = 不启用 AI</li>
 *   <li>{@code REPAIR_AI_QUERY_TIMEOUT_MS}：单条 SQL 的执行超时，默认 3000（第 4 层）</li>
 *   <li>{@code REPAIR_AI_MAX_ROWS}：返回行数上限，默认 1000（第 4 层）</li>
 *   <li>{@code REPAIR_AI_RATE_LIMIT_MAX_REQUESTS}：问数接口每分钟上限，默认 10（**它是花钱的接口**）</li>
 * </ul>
 *
 * <p><b>为什么不给 Key 设默认值</b>：AI_MODEL_API_KEY 留空就是"不启用 AI"，这是合法状态
 * （应用启动不依赖模型，与"启动不依赖 MinIO"同一条原则）。但只读账号的两个变量**要么都填、
 * 要么都不填**——只填一个的话，现象是"问数接口报连接失败"，排查要绕一圈才知道是配置漏了，
 * 所以照 {@code PlatformProperties} 的做法：启动就报错。
 *
 * <p>阈值走 {@code ${ENV:默认值}} 占位、集中在配置类里（照 {@code TimeoutRule} / {@code RateLimitRule}）。
 * 默认 3 秒是 ADR-003 定的：问数面向的是"看一眼数据"的交互，不该出现跑十几秒的查询；
 * 超时返回 {@code 40003}，文案就是"请缩小查询范围"。
 */
@Getter
@Component
public class AiProperties {

    private final String apiKey;

    private final String baseUrl;

    private final String modelName;

    private final String dbUsername;

    private final String dbPassword;

    private final int queryTimeoutMs;

    private final int maxRows;

    private final int rateLimitMaxRequests;

    public AiProperties(@Value("${AI_MODEL_API_KEY:}") String apiKey,
                        @Value("${AI_MODEL_BASE_URL:https://api.deepseek.com}") String baseUrl,
                        @Value("${AI_MODEL_NAME:deepseek-chat}") String modelName,
                        @Value("${AI_DB_USERNAME:}") String dbUsername,
                        @Value("${AI_DB_PASSWORD:}") String dbPassword,
                        @Value("${repair.ai.query-timeout-ms:3000}") int queryTimeoutMs,
                        @Value("${repair.ai.max-rows:1000}") int maxRows,
                        @Value("${repair.ai.rate-limit-max-requests:10}") int rateLimitMaxRequests) {
        boolean hasUsername = StringUtils.hasText(dbUsername);
        boolean hasPassword = StringUtils.hasText(dbPassword);
        if (hasUsername != hasPassword) {
            throw new IllegalStateException(
                    "只读数据库账号的配置只填了一半：AI_DB_USERNAME 与 AI_DB_PASSWORD 要么都填、要么都不填。"
                            + "（两个都留空 = 不启用 AI 问数，这时问数接口会返回 40004）");
        }
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
        this.queryTimeoutMs = queryTimeoutMs;
        this.maxRows = maxRows;
        this.rateLimitMaxRequests = rateLimitMaxRequests;
    }

    /** 模型凭据配了没（没配 = 不启用 AI，接口回 40004 而不是启动失败）。 */
    public boolean modelConfigured() {
        return StringUtils.hasText(apiKey);
    }

    /** 只读账号配了没（没配就没有连接池，也不去建账号）。 */
    public boolean readOnlyAccountConfigured() {
        return StringUtils.hasText(dbUsername) && StringUtils.hasText(dbPassword);
    }

    /** 能不能用：模型与只读账号都配好才算。 */
    public boolean enabled() {
        return modelConfigured() && readOnlyAccountConfigured();
    }

    /**
     * JDBC 的 {@code setQueryTimeout} 只认**秒**，所以这里做一次转换。
     * 小于 1 秒的一律按 1 秒（0 在 JDBC 里是"不限制"，那正是最不该出现的情况）。
     */
    public int queryTimeoutSeconds() {
        return Math.max(1, queryTimeoutMs / 1000);
    }
}
