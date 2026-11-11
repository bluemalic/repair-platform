package com.bluemalic.repair.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * SSE 的工作线程池（{@code GET /api/ai/query/stream} 用）。
 *
 * <p><b>为什么必须有这个池</b>：Controller 返回 {@code SseEmitter} 之后请求线程就释放了，
 * 事件只能从别的线程写；而这一轮工作在**等模型**（两次调用，十几秒），不能占着 Tomcat 的请求线程。
 *
 * <p><b>为什么不用 Spring Boot 自动装配的 {@code applicationTaskExecutor}</b>：那个池是全应用
 * 异步任务的共用品（默认 8 个线程），而流式问数每个线程会阻塞十几秒，占满之后受影响的是别的异步任务。
 * 给它一个独立的小池，就把"AI 慢"和"别的异步任务"隔开了。
 *
 * <p>参数怎么定的：4 个线程够几个后勤管理员同时问（每次问数限制 10 次/分钟，也不太可能真有 4 个人
 * 卡在同一秒）；队列 32 是缓冲，**满了就拒绝**——拒绝会立刻回一个错误帧（见 {@code AiQueryStreamer}），
 * 这比让请求堆在队列里排到超时有用得多。
 */
@Configuration
public class AiStreamConfig {

    @Bean("aiStreamExecutor")
    public ThreadPoolTaskExecutor aiStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("ai-stream-");
        // 关服时给进行中的问数一点时间收尾，否则用户看到的是"莫名断流"
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
