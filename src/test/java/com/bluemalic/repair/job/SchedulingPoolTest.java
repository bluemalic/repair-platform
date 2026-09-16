package com.bluemalic.repair.job;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.bluemalic.repair.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调度线程池的回归守卫。
 *
 * <p>为什么值得钉一条断言：超时调度有 4 个任务（3 个秒级消费 + 1 个每分钟兜底），
 * Spring 默认池大小是 1——**兜底扫描一旦超过 1 秒就会吃掉秒级消费的窗口，触发精度退化回分钟级**，
 * 而那正是 ADR-001 用 ZSet 替换定时轮询时要消灭的问题。
 * 这条断言让"有人删掉 `spring.task.scheduling.pool.size`"这件事在 CI 上直接暴露。
 */
@IntegrationTest
class SchedulingPoolTest {

    @Autowired
    private ThreadPoolTaskScheduler taskScheduler;

    @Test
    void schedulerHasEnoughThreadsForAllTimeoutTasks() {
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                .as("超时调度有 4 个任务，线程池至少要有 4 个线程（application.yml: spring.task.scheduling.pool.size）")
                .isGreaterThanOrEqualTo(4);
    }
}