package com.bluemalic.repair.job;

import com.bluemalic.repair.config.TimeoutProperties;
import com.bluemalic.repair.service.TimeoutScanner;
import com.bluemalic.repair.service.impl.TimeoutScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 超时调度任务（M3，ADR-001 + docs/01 §超时；阶段 4 加"扫描触发方式"开关）：
 * <ul>
 *   <li>秒级消费 ZSet 到期任务（多实例去重逻辑在 TimeoutService 里，ZREM 返回值判定）</li>
 *   <li>按 {@code repair.timeout.scan.trigger} 决定兜底扫描由谁触发：
 *       {@code scheduled}（默认，每分钟一次）、{@code xxl-job}（由 {@link TimeoutScanHandler} 调）、
 *       {@code off}（本地调试时彻底关掉）</li>
 * </ul>
 *
 * <p><b>delivery=mq 时（阶段 2 · ADR-024）</b>：秒级 ZSet 消费关闭（到期由 RocketMQ 定时消息触发，
 * timeout-service 的 {@code TimeoutMqConsumer} 执行）；兜底扫描仍按 {@code backstop-enabled}
 * 留在单体——它是消息丢失的最后一道防线。
 *
 * <p>这里只做"编排"：到期工单该做什么由 {@code TimeoutScanService} 里的三个动作决定。
 *
 * <p><b>测试里必须关掉</b>（{@code repair.timeout.scheduler-enabled=false}，见测试注解
 * {@code @IntegrationTest}）：集成测试用 @Transactional 造数据，后台线程既看不到未提交数据，
 * 又会在库里/Redis 上反复空转刷日志；超时逻辑本身由 TimeoutAutoCloseTest 直接调服务方法验证。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "repair.timeout", name = "scheduler-enabled", matchIfMissing = true)
public class TimeoutScheduler {

    private final TimeoutScanner timeoutScanner;
    private final TimeoutScanService scanService;
    private final TimeoutProperties timeoutProperties;

    /**
     * 兜底扫描的触发方式（阶段 4 · ADR-029）。
     *
     * <p>为什么要有这个开关：2C2G 的 ops 节点上跑 XXL-JOB Admin 时，扫描由它的分片任务触发
     * （多实例各扫一片）；而线上 lite 只有一个 timeout 进程，用 XXL-JOB 是额外的运维负担，
     * 保持 {@code scheduled} 即可。
     */
    @Value("${repair.timeout.scan.trigger:scheduled}")
    private String scanTrigger;

    /**
     * 任务名 → 上一次已经 WARN 过的失败信息（见 {@link #runQuietly}）。
     * 四个任务跑在调度线程池的不同线程上，所以用并发容器。
     */
    private final Map<String, String> lastWarned = new ConcurrentHashMap<>();

    /** ADR-001：秒级消费，触发精度 ≈ 阈值 + 1s。mq 模式下由定时消息触发，这里不轮询。 */
    @Scheduled(fixedDelay = 1000)
    public void consumeDueEval() {
        if (!timeoutProperties.isZsetDelivery()) {
            return;
        }
        runQuietly("消费到期任务[验收]", () -> scanService.closeDue(timeoutScanner.handleDueEval()));
    }

    /** 未接单提醒节点：到期仍无人接单 → 提醒调度方（docs/01 §超时）。 */
    @Scheduled(fixedDelay = 1000)
    public void consumeDueAccept() {
        if (!timeoutProperties.isZsetDelivery()) {
            return;
        }
        runQuietly("消费到期任务[接单]", () -> scanService.remindDue(timeoutScanner.handleDueAccept()));
    }

    /** 未处理升级节点：派单超阈值仍未完工 → 升级提醒调度方（docs/01 §超时）。 */
    @Scheduled(fixedDelay = 1000)
    public void consumeDueProcess() {
        if (!timeoutProperties.isZsetDelivery()) {
            return;
        }
        runQuietly("消费到期任务[处理]", () -> scanService.escalateDue(timeoutScanner.handleDueProcess()));
    }

    /** docs/01 §超时：每分钟兜底扫描一次数据库，独立于 ZSet/MQ 路径（三个节点都扫）。 */
    @Scheduled(cron = "0 * * * * *")
    public void backstop() {
        if (!timeoutProperties.isBackstopEnabled()) {
            return;
        }
        if (!"scheduled".equalsIgnoreCase(scanTrigger)) {
            // xxl-job 模式由 TimeoutScanHandler 按分片触发；off 模式什么都不做
            return;
        }
        runQuietly("兜底扫描", () -> scanService.scan(0, 1));
    }

    /**
     * 调度任务不向上抛异常：Redis/数据库短暂不可用是常态，抛出去会被 Spring 的 TaskUtils
     * 按 ERROR 打整段堆栈、每秒一次，把日志冲垮。这里降成一行 WARN，恢复后自然继续。
     *
     * <p><b>同一类失败只提醒一次</b>：一个任务每秒跑一轮，Redis 挂掉时三个消费任务会各刷一行——
     * 实测每秒 3 行、一天 20 多万行，真正的问题反而被淹掉。所以按「任务 + 失败信息」去重：
     * 第一次 WARN，之后的同类失败降 DEBUG；**恢复后清除标记**，下一次故障还会重新提醒。
     * 失败信息变化（比如 Redis 好了但数据库挂了）也会立刻 WARN，不会把新问题吞掉。
     *
     * <p>与 {@code RateLimitInterceptor} 的降级日志是同一条原则：故障期间日志要能说明问题，
     * 而不是用它自己的量把问题埋了。
     */
    private void runQuietly(String task, Runnable body) {
        try {
            body.run();
            // 恢复即重置：下次故障重新提醒一次
            lastWarned.remove(task);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (!message.equals(lastWarned.put(task, message))) {
                log.warn("超时调度[{}]本轮失败（同类失败此后只记 DEBUG，恢复后重新提醒）：{}", task, message);
            } else {
                log.debug("超时调度[{}]仍失败：{}", task, message);
            }
        }
    }
}
