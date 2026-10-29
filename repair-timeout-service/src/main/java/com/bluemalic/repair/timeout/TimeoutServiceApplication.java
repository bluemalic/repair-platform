package com.bluemalic.repair.timeout;

import com.bluemalic.repair.common.MetricsRecorder;
import com.bluemalic.repair.config.TenantDataSourceConfig;
import com.bluemalic.repair.config.TenancyProperties;
import com.bluemalic.repair.config.TimeoutRule;
import com.bluemalic.repair.job.TimeoutScheduler;
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
 * NoticePublisher）、Outbox 写入、通知写路径、超时机制与处理器、调度器。
 * 没有 Web 容器、没有 Sa-Token、没有工单命令接口——它不受理用户操作，只消费超时任务。
 *
 * <p><b>与单体并行的安全性（zset 模式，当前生产口径）</b>：秒级消费按 ZREM 返回值去重
 * （ADR-001，多实例天然安全）；兜底扫描在两个进程里都会跑，但动作本身幂等
 * （notifyTimeoutOnce 按状态 + ticket_log 判重，autoClose 是状态 CAS）——双跑只浪费一轮查询，
 * 不会重复提醒或重复关闭。按 ADR-024，兜底的长住归属是单体；等 delivery=mq（下一刀）时
 * 再把"谁跑兜底"用开关收敛，本进程只保留毫秒级路径。
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
        TimeoutServiceImpl.class,
        TicketTimeoutHandler.class,
        TimeoutScheduler.class
})
public class TimeoutServiceApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(TimeoutServiceApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
