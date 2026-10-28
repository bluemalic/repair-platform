package com.bluemalic.repair.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时调度。超时升级（M3）的秒级 ZSet 消费 + 每分钟兜底扫描都挂在这里（ADR-001）。
 *
 * <p><b>线程池大小在 application.yml 里显式配了 4</b>（`spring.task.scheduling.pool.size`）：
 * Spring 默认只有 1 个线程，而这里有 4 个任务（3 个 `fixedDelay=1000` 的消费 + 1 个每分钟兜底）。
 * 单线程串行的后果是：**兜底扫描一旦超过 1 秒，就会吃掉秒级消费的调度窗口，触发精度退化回分钟级**——
 * 那正是 ADR-001 用 ZSet 替换定时轮询时要消灭的问题。所以宁可多给几个线程，也不让它们互相拖累。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}