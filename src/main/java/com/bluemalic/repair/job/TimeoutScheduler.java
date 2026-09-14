package com.bluemalic.repair.job;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.service.TimeoutService;
import com.bluemalic.repair.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeoutScheduler {

    private final TimeoutService timeoutService;
    private final TicketService ticketService;

    /** ADR-001：秒级消费，触发精度 ≈ 阈值 + 1s。 */
    @Scheduled(fixedDelay = 1000)
    public void consumeDueEval() {
        closeDue(timeoutService.handleDueEval());
    }

    /** docs/01 §超时：每分钟兜底扫描一次数据库，独立于 ZSet 路径。 */
    @Scheduled(cron = "0 * * * * *")
    public void backstop() {
        closeDue(timeoutService.backstopScanEval());
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
}