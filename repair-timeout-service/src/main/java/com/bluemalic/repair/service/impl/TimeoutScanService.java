package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.service.DueTicket;
import com.bluemalic.repair.service.TimeoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 超时的**兜底扫描**（阶段 4 · ADR-029）。
 *
 * <p>它回答两个问题：扫哪些库（分片）、扫到之后做什么（三个动作）。
 * 秒级/定时消息的消费路径不在这里——那条路只处理"Redis ZSet 或 RocketMQ 还记着"的任务；
 * 这里是**最后一道防线**：Redis 重启丢了任务、消息投递失败、或者进程在登记前就挂了，
 * 靠扫库把"该提醒/该关闭却还停在那一步"的工单捞回来。
 *
 * <p><b>分片是给多实例用的</b>：{@code scan(shardIndex, shardTotal)} 只处理
 * {@code tenantId % shardTotal == shardIndex} 的库（平台库按 tenantId=0 参与）。
 * 单实例用 {@code scan(0, 1)}——传 (0, n) 会让每所学校每分钟只被扫 n 分之一，等于降级。
 *
 * <p><b>幂等</b>：三个动作内部都按"状态 + ticket_log 是否已记过"判定，重复扫到只会空转，
 * 不会重复提醒/重复关闭（见 {@link TicketTimeoutHandler}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeoutScanService {

    private final TimeoutService timeoutService;

    private final TicketTimeoutHandler timeoutHandler;

    /**
     * 按分片扫一轮（三个节点都扫）。
     *
     * @param shardIndex 本执行器负责的分片序号（0 基）
     * @param shardTotal 分片总数（单实例传 1）
     * @return 本轮实际尝试处理的条数
     */
    public int scan(int shardIndex, int shardTotal) {
        int handled = closeDue(timeoutService.backstopScanEval(shardIndex, shardTotal));
        handled += remindDue(timeoutService.backstopScanAccept(shardIndex, shardTotal));
        handled += escalateDue(timeoutService.backstopScanProcess(shardIndex, shardTotal));
        if (handled > 0) {
            log.info("超时兜底扫描完成 shard={}/{} 处理 {} 条", shardIndex, shardTotal, handled);
        }
        return handled;
    }

    /** 验收超时 → 自动关闭。 */
    public int closeDue(List<DueTicket> due) {
        int handled = 0;
        for (DueTicket task : due) {
            try {
                timeoutHandler.autoClose(task);
                handled++;
                log.info("超时自动关闭 tenantId={} ticketId={}", task.tenantId(), task.ticketId());
            } catch (BizException e) {
                // 状态已不是 50（人工关闭/撤单/已被另一实例处理）→ 正常跳过，不算失败
                log.info("超时任务跳过（工单已流转） tenantId={} ticketId={} reason={}",
                        task.tenantId(), task.ticketId(), e.getMessage());
            } catch (Exception e) {
                // 单工单失败不影响本轮其余任务
                log.warn("超时自动关闭失败 tenantId={} ticketId={}", task.tenantId(), task.ticketId(), e);
            }
        }
        return handled;
    }

    /** 未接单提醒。 */
    public int remindDue(List<DueTicket> due) {
        int handled = 0;
        for (DueTicket task : due) {
            try {
                // 幂等与"是否还该提醒"都在 remindAcceptTimeout 里判定（状态 + ticket_log）；
                // 按租户钉库也在 handler 里做（任务带着租户，见 DueTicket）
                timeoutHandler.remindAcceptTimeout(task);
                handled++;
            } catch (BizException e) {
                log.info("接单提醒跳过（工单已流转） tenantId={} ticketId={} reason={}",
                        task.tenantId(), task.ticketId(), e.getMessage());
            } catch (Exception e) {
                log.warn("接单提醒失败 tenantId={} ticketId={}", task.tenantId(), task.ticketId(), e);
            }
        }
        return handled;
    }

    /** 处理超时升级。 */
    public int escalateDue(List<DueTicket> due) {
        int handled = 0;
        for (DueTicket task : due) {
            try {
                timeoutHandler.escalateProcessTimeout(task);
                handled++;
            } catch (BizException e) {
                log.info("处理超时升级跳过（工单已流转） tenantId={} ticketId={} reason={}",
                        task.tenantId(), task.ticketId(), e.getMessage());
            } catch (Exception e) {
                log.warn("处理超时升级失败 tenantId={} ticketId={}", task.tenantId(), task.ticketId(), e);
            }
        }
        return handled;
    }
}
