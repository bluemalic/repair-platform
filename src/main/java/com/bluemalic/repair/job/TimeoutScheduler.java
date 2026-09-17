package com.bluemalic.repair.job;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.service.TimeoutService;
import com.bluemalic.repair.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 超时调度任务（M3，ADR-001 + docs/01 §超时）：
 * <ul>
 *   <li>秒级消费 ZSet 到期任务（多实例去重逻辑在 TimeoutService 里，ZREM 返回值判定）</li>
 *   <li>每分钟兜底扫库，补 ZSet 丢失（Redis 重启）导致的漏处理</li>
 * </ul>
 *
 * <p>这里只做"编排"：到期工单该做什么（自动关闭）由 {@link TicketService#autoClose} 决定，
 * 调度器不直接碰库。
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

    private final TimeoutService timeoutService;
    private final TicketService ticketService;

    /**
     * 任务名 → 上一次已经 WARN 过的失败信息（见 {@link #runQuietly}）。
     * 四个任务跑在调度线程池的不同线程上，所以用并发容器。
     */
    private final Map<String, String> lastWarned = new ConcurrentHashMap<>();

    /** ADR-001：秒级消费，触发精度 ≈ 阈值 + 1s。 */
    @Scheduled(fixedDelay = 1000)
    public void consumeDueEval() {
        runQuietly("消费到期任务[验收]", () -> closeDue(timeoutService.handleDueEval()));
    }

    /** 未接单提醒节点：到期仍无人接单 → 提醒调度方（docs/01 §超时）。 */
    @Scheduled(fixedDelay = 1000)
    public void consumeDueAccept() {
        runQuietly("消费到期任务[接单]", () -> remindDue(timeoutService.handleDueAccept()));
    }

    /** 未处理升级节点：派单超阈值仍未完工 → 升级提醒调度方（docs/01 §超时）。 */
    @Scheduled(fixedDelay = 1000)
    public void consumeDueProcess() {
        runQuietly("消费到期任务[处理]", () -> escalateDue(timeoutService.handleDueProcess()));
    }

    /** docs/01 §超时：每分钟兜底扫描一次数据库，独立于 ZSet 路径（三个节点都扫）。 */
    @Scheduled(cron = "0 * * * * *")
    public void backstop() {
        runQuietly("兜底扫描[验收]", () -> closeDue(timeoutService.backstopScanEval()));
        runQuietly("兜底扫描[接单]", () -> remindDue(timeoutService.backstopScanAccept()));
        runQuietly("兜底扫描[处理]", () -> escalateDue(timeoutService.backstopScanProcess()));
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

    private void closeDue(List<Long> ticketIds) {
        for (Long ticketId : ticketIds) {
            try {
                ticketService.autoClose(ticketId);
                log.info("超时自动关闭 ticketId={}", ticketId);
            } catch (BizException e) {
                // 状态已不是 50（人工关闭/撤单/已被另一实例处理）→ 正常跳过，不算失败
                log.info("超时任务跳过（工单已流转） ticketId={} reason={}", ticketId, e.getMessage());
            } catch (Exception e) {
                // 单工单失败不影响本轮其余任务
                log.warn("超时自动关闭失败 ticketId={}", ticketId, e);
            }
        }
    }

    private void remindDue(List<Long> ticketIds) {
        for (Long ticketId : ticketIds) {
            try {
                // 幂等与"是否还该提醒"都在 remindAcceptTimeout 里判定（状态 + ticket_log）
                ticketService.remindAcceptTimeout(ticketId);
            } catch (BizException e) {
                log.info("接单提醒跳过（工单已流转） ticketId={} reason={}", ticketId, e.getMessage());
            } catch (Exception e) {
                log.warn("接单提醒失败 ticketId={}", ticketId, e);
            }
        }
    }

    private void escalateDue(List<Long> ticketIds) {
        for (Long ticketId : ticketIds) {
            try {
                ticketService.escalateProcessTimeout(ticketId);
            } catch (BizException e) {
                log.info("处理超时升级跳过（工单已流转） ticketId={} reason={}", ticketId, e.getMessage());
            } catch (Exception e) {
                log.warn("处理超时升级失败 ticketId={}", ticketId, e);
            }
        }
    }
}