package com.bluemalic.repair.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时调度。超时升级（M3）的秒级 ZSet 消费 + 每分钟兜底扫描都挂在这里（ADR-001）。
 *
 * <p>默认单线程调度器即可：两个任务都极短（扫 ZSet / 扫到期工单并条件更新），
 * 不会互相拖累；将来任务变重再换线程池。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}