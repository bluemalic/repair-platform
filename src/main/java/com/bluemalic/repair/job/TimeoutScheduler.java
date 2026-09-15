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

    /** docs/01 §超时：每分钟兜底扫描一次数据库，独立于 ZSet 路径（两个节点都扫）。 */
    @Scheduled(cron = "0 * * * * *")
    public void backstop() {
        runQuietly("兜底扫描[验收]", () -> closeDue(timeoutService.backstopScanEval()));
        runQuietly("兜底扫描[接单]", () -> remindDue(timeoutService.backstopScanAccept()));
    }

    /**
     * 调度任务不向上抛异常：Redis/数据库短暂不可用是常态，抛出去会被 Spring 的 TaskUtils
     * 按 ERROR 打整段堆栈、每秒一次，把日志冲垮。这里降成一行 WARN，恢复后自然继续。
     */
    private void runQuietly(String task, Runnable body) {
        try {
            body.run();
        } catch (Exception e) {
            log.warn("超时调度[{}]本轮失败（下一轮继续）：{}", task, e.getMessage());
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
}