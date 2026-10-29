package com.bluemalic.repair.timeout;

import com.bluemalic.repair.common.MetricsRecorder;
import com.bluemalic.repair.config.TenantDataSourceConfig;
import com.bluemalic.repair.config.TenancyProperties;
import com.bluemalic.repair.config.TimeoutMqConfig;
import com.bluemalic.repair.config.TimeoutProperties;
import com.bluemalic.repair.config.TimeoutRule;
import com.bluemalic.repair.job.TimeoutScheduler;
import com.bluemalic.repair.mq.TimeoutMqConsumer;
import com.bluemalic.repair.mq.TimeoutMqScheduler;
import com.bluemalic.repair.service.impl.NotificationServiceImpl;
import com.bluemalic.repair.service.impl.OutboxServiceImpl;
import com.bluemalic.repair.service.impl.TicketLookup;
import com.bluemalic.repair.service.impl.TicketNoticePublisher;
import com.bluemalic.repair.service.impl.TicketTimeoutHandler;
import com.bluemalic.repair.service.impl.TicketTransitionSupport;
import com.bluemalic.repair.service.impl.TimeoutServiceImpl;
import com.bluemalic.repair.tenancy.TenantDataSourceRegistry;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * timeout-service 的独立进程入口（阶段 2 · ADR-024）。
 *
 * <p>只导入超时链路需要的 Bean：数据源与租户路由、工单流转内核（TransitionSupport / Lookup /
 * NoticePublisher）、Outbox 写入、通知写路径、超时机制与处理器、调度器；
 * {@code delivery=mq} 时再按条件装配定时消息的生产者/消费者（{@link TimeoutMqConfig}）。
 * 没有 Web 容器、没有 Sa-Token、没有工单命令接口——它不受理用户操作，只消费超时任务。
 *
 * <p><b>两种投递模式</b>（{@code repair.timeout.delivery}）：
 * <ul>
 *   <li>{@code zset}（默认）：秒级消费 Redis ZSet（ADR-001）。与单体并行也安全（ZREM 去重、
 *       动作幂等），但兜底会双跑一轮；本进程通常只在 mq 模式下启动。</li>
 *   <li>{@code mq}：单体在登记点发 RocketMQ 定时消息，本进程消费到期消息执行三个动作；
 *       ZSet 秒级消费关闭，兜底扫库按 ADR-024 留在单体（本进程设 backstop-enabled=false）。</li>
 * </ul>
 *
 * <p>启动时关掉 Flyway（表结构由单体负责）：见 {@code application-timeout.yml}。
 * 调度线程池沿用 application.yml 的 {@code spring.task.scheduling.pool.size}（随 common 进 classpath）。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.bluemalic.repair.mapper")
@Import({
        TenantDataSourceConfig.class,
        TenancyProperties.class,
        TenantDataSourceRegistry.class,
        TenantSchemaCatalog.class,
        MetricsRecorder.class,
        OutboxServiceImpl.class,
        TicketTransitionSupport.class,
        TicketLookup.class,
        TicketNoticePublisher.class,
        NotificationServiceImpl.class,
        TimeoutRule.class,
        TimeoutProperties.class,
        TimeoutServiceImpl.class,
        TicketTimeoutHandler.class,
        TimeoutScheduler.class,
        TimeoutMqScheduler.class,
        TimeoutMqConsumer.class,
        TimeoutMqConfig.class
})
public class TimeoutServiceApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(TimeoutServiceApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
