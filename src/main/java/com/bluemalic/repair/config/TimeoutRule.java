package com.bluemalic.repair.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 超时规则集中配置。开发/演示时可用环境变量 {@code REPAIR_TIMEOUT_EVAL_MINUTES} 覆盖为小值，
 * 例如 1（分钟）即可在本地快速看到"验收超时自动关闭"跑一遍；生产保持默认 24h。
 */
@Getter
@Component
public class TimeoutRule {

    /** 验收超时：50 已完成 超过该时长未关闭 → 自动流转 60（docs/02 状态机 "50→60 超时自动关"）。 */
    private final long evalMinutes;

    public TimeoutRule(@Value("${repair.timeout.eval-minutes:1440}") long evalMinutes) {
        this.evalMinutes = evalMinutes;
    }

    public Duration evalDuration() {
        return Duration.ofMinutes(evalMinutes);
    }
}