package com.bluemalic.repair.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 超时规则集中配置。开发/演示时可用环境变量覆盖为小值，例如各设为 1（分钟），
 * 即可在本地快速看到"超时提醒/自动关闭"跑一遍；生产保持默认。
 *
 * <ul>
 *   <li>{@code REPAIR_TIMEOUT_ACCEPT_MINUTES}：未接单提醒阈值（默认 24h）</li>
 *   <li>{@code REPAIR_TIMEOUT_PROCESS_MINUTES}：未处理升级阈值（默认 48h）</li>
 *   <li>{@code REPAIR_TIMEOUT_EVAL_MINUTES}：验收超时自动关闭阈值（默认 24h）</li>
 * </ul>
 */
@Getter
@Component
public class TimeoutRule {

    /** 未接单提醒：20 待接单 超过该时长仍无人接单 → 提醒调度方（docs/01 §超时）。 */
    private final long acceptMinutes;

    /** 未处理升级：30 处理中 自派单起超过该时长仍未完工 → 升级提醒调度方（docs/01 §超时）。 */
    private final long processMinutes;

    /** 验收超时：50 已完成 超过该时长未关闭 → 自动流转 60（docs/02 状态机 "50→60 超时自动关"）。 */
    private final long evalMinutes;

    public TimeoutRule(@Value("${repair.timeout.accept-minutes:1440}") long acceptMinutes,
                       @Value("${repair.timeout.process-minutes:2880}") long processMinutes,
                       @Value("${repair.timeout.eval-minutes:1440}") long evalMinutes) {
        this.acceptMinutes = acceptMinutes;
        this.processMinutes = processMinutes;
        this.evalMinutes = evalMinutes;
    }

    public Duration acceptDuration() {
        return Duration.ofMinutes(acceptMinutes);
    }

    public Duration processDuration() {
        return Duration.ofMinutes(processMinutes);
    }

    public Duration evalDuration() {
        return Duration.ofMinutes(evalMinutes);
    }

    /**
     * 阈值的中文文案，用在通知正文里。演示时阈值被调成 1 分钟，硬写"24 小时"就会说谎，
     * 所以按实际配置生成。
     */
    public String acceptThresholdText() {
        return humanText(acceptMinutes);
    }

    public String processThresholdText() {
        return humanText(processMinutes);
    }

    private String humanText(long minutes) {
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        if (minutes % 60 == 0) {
            return (minutes / 60) + " 小时";
        }
        return (minutes / 60) + " 小时 " + (minutes % 60) + " 分钟";
    }
}