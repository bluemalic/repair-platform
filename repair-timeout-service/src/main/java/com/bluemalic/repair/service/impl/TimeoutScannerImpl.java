package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bluemalic.repair.common.TicketStatus;
import com.bluemalic.repair.config.TimeoutRule;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.mapper.TicketEvaluationMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.DueTicket;
import com.bluemalic.repair.service.TimeoutKeys;
import com.bluemalic.repair.service.TimeoutScanner;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 超时的**消费与扫描**（ADR-001 落地；阶段 3.4 从 {@code TimeoutServiceImpl} 拆出）。
 *
 * <p>ZSet：key = {@code ticket:timeout:{节点}}，member = {@code 租户ID|工单ID}，
 * score = 到期毫秒时间戳。消费时先 ZREM，返回 1 的实例才处理 → 多实例天然去重。
 * 键与成员格式的唯一来源是 {@link TimeoutKeys}（登记侧也用它）。
 *
 * <p><b>成员为什么带租户 ID</b>（阶段 6 第 3 步 · ADR-021）：调度器没有登录态，
 * 消费一条任务时要知道去**哪个库**处理；而成员是登记当时写下的，学校搬家之后那个库就不对了。
 * 所以成员里存租户 ID，消费时经 {@code TenantSchemaCatalog} 换成**当前**的库名——
 * 切换前后登记的任务都会落到现在的库上（细节见 {@link DueTicket}）。
 *
 * <p><b>兜底扫描要逐库跑</b>：数据在多个库里，一次 SQL 扫不到（平台库 + 每个已搬家的学校库）。
 * 于是每轮对每个库各扫一次，每次都在"那个库"的路由键下执行；扫出来的每条任务带着
 * `tenant_id`，消费时再解析成当前库名（所以即便扫到的是搬家前留在平台库的旧副本，
 * 处理时也会落到那所学校**现在**的库上）。
 *
 * <p>其余语义没变：调度器侧仍是"系统上下文"（数据权限拦截器对 null loginId 不注入条件），
 * 状态过滤与单轮上限仍由这里自己带（漏了状态过滤，N 会单调增长）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeoutScannerImpl implements TimeoutScanner {

    /**
     * 单轮**每个库**的扫描上限：防止一次把历史上所有超期工单拉进内存（下一分钟继续处理剩下的）。
     * 学校多了之后一轮最多是「学校数 × 这个数」，量级仍然可控。
     */
    private static final int SCAN_LIMIT = 200;

    private final StringRedisTemplate stringRedisTemplate;
    private final TimeoutRule timeoutRule;
    private final TicketMapper ticketMapper;
    private final TicketEvaluationMapper ticketEvaluationMapper;
    private final TenantSchemaCatalog schemaCatalog;

    @Override
    public List<DueTicket> handleDueAccept() {
        return takeDue(TimeoutKeys.NODE_ACCEPT);
    }

    @Override
    public List<DueTicket> handleDueProcess() {
        return takeDue(TimeoutKeys.NODE_PROCESS);
    }

    @Override
    public List<DueTicket> handleDueEval() {
        return takeDue(TimeoutKeys.NODE_EVAL);
    }

    @Override
    public List<DueTicket> backstopScanAccept(int shardIndex, int shardTotal) {
        LocalDateTime cutoff = LocalDateTime.now().minus(timeoutRule.acceptDuration());
        return scanOverdueTickets(List.of(TicketStatus.TO_ACCEPT.getCode()), cutoff, shardIndex, shardTotal);
    }

    @Override
    public List<DueTicket> backstopScanProcess(int shardIndex, int shardTotal) {
        LocalDateTime cutoff = LocalDateTime.now().minus(timeoutRule.processDuration());
        // 35 待料不暂停 SLA：它也属于"派出去还没完工"，Redis 丢任务后的兜底必须一起捞
        return scanOverdueTickets(
                List.of(TicketStatus.PROCESSING.getCode(), TicketStatus.WAITING_MATERIAL.getCode()), cutoff,
                shardIndex, shardTotal);
    }

    @Override
    public List<DueTicket> backstopScanEval(int shardIndex, int shardTotal) {
        LocalDateTime cutoff = LocalDateTime.now().minus(timeoutRule.evalDuration());
        List<DueTicket> result = new ArrayList<>();
        for (String routingKey : scanTargets(shardIndex, shardTotal)) {
            List<String> members = TenantRoutingContext.call(routingKey,
                    () -> ticketEvaluationMapper.selectOverdueOpenTicketMembers(cutoff, SCAN_LIMIT));
            members.forEach(member -> result.add(parseMember(member)));
        }
        return result;
    }

    /**
     * 兜底扫描的目标库：**平台库 + 属于本分片的学校库**。
     *
     * <p>平台库那一份要扫，因为存量租户的数据还在那里；已搬家的学校库要逐个扫，因为它们的
     * 活数据在那儿（平台库里那份只是搬走时的旧副本——扫到也不怕：任务带着 `tenant_id`，
     * 处理时会落到那所学校**现在**的库上）。
     */
    private List<String> scanTargets(int shardIndex, int shardTotal) {
        if (shardTotal < 1 || shardIndex < 0 || shardIndex >= shardTotal) {
            throw new IllegalArgumentException("非法分片参数 shardIndex=" + shardIndex + " shardTotal=" + shardTotal);
        }
        List<String> routingKeys = new ArrayList<>();
        // 平台库按 tenantId = 0 参与取模：分片数 > 1 时只有 0 号分片扫它
        if (0 % shardTotal == shardIndex) {
            routingKeys.add(TenantRoutingContext.CONTROL);
        }
        for (Map.Entry<Long, String> entry : schemaCatalog.provisionedSchemas().entrySet()) {
            long tenantId = entry.getKey() == null ? 0L : entry.getKey();
            if (tenantId % shardTotal != shardIndex) {
                continue;
            }
            if (!routingKeys.contains(entry.getValue())) {
                routingKeys.add(entry.getValue());
            }
        }
        return routingKeys;
    }

    /**
     * 在**每个库**里找"该提醒/该升级、却还停在那一步"的工单（Redis 重启丢任务时的兜底）。
     *
     * <p>状态过滤与单轮上限都必须带：漏了状态过滤，N 会单调增长（每分钟 N 次无效查询 + N 行日志）。
     * 查询本身不带 `tenant_id`——它要在一整个库里找，租户归属由返回的 `tenant_id` 列带回来。
     */
    private List<DueTicket> scanOverdueTickets(List<Integer> statuses, LocalDateTime cutoff,
                                               int shardIndex, int shardTotal) {
        List<DueTicket> result = new ArrayList<>();
        for (String routingKey : scanTargets(shardIndex, shardTotal)) {
            List<DueTicket> found = TenantRoutingContext.call(routingKey, () -> {
                Page<Ticket> page = ticketMapper.selectPage(new Page<>(1, SCAN_LIMIT, false),
                        Wrappers.<Ticket>lambdaQuery()
                                .in(Ticket::getStatus, statuses)
                                .lt(Ticket::getDispatchTime, cutoff)
                                .orderByAsc(Ticket::getDispatchTime)
                                .select(Ticket::getId, Ticket::getTenantId));
                return page.getRecords().stream()
                        .map(ticket -> new DueTicket(ticket.getTenantId(), ticket.getId()))
                        .toList();
            });
            result.addAll(found);
        }
        return result;
    }

    private List<DueTicket> takeDue(String node) {
        String zsetKey = TimeoutKeys.key(node);
        var zSet = stringRedisTemplate.opsForZSet();
        var due = zSet.rangeByScore(zsetKey, 0, System.currentTimeMillis());
        if (due == null || due.isEmpty()) {
            return List.of();
        }
        return due.stream()
                .filter(member -> {
                    // 只有 ZREM 成功的执行者才拿到这个任务（多实例唯一消费）
                    Long removed = zSet.remove(zsetKey, member);
                    return removed != null && removed > 0;
                })
                .map(TimeoutScannerImpl::parseMember)
                .toList();
    }

    /**
     * 解析 ZSet 成员。没有分隔符的是**本机制上线前**登记的成员（只有工单 ID）：
     * 那时所有学校都还在平台库，按平台库处理是对的，这批成员消费完就没了。
     */
    private static DueTicket parseMember(String member) {
        int separator = member.indexOf(TimeoutKeys.MEMBER_SEPARATOR);
        if (separator < 0) {
            return new DueTicket(DueTicket.LEGACY_TENANT, Long.parseLong(member));
        }
        return new DueTicket(Long.parseLong(member.substring(0, separator)),
                Long.parseLong(member.substring(separator + 1)));
    }
}
