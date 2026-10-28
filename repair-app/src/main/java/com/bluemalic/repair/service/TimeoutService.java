package com.bluemalic.repair.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时调度（ADR-001：Redis ZSet 延迟队列，ZREM 返回值做多实例去重）。
 *
 * <p>三个节点各用一个 ZSet key（{@code ticket:timeout:ACCEPT} / {@code :PROCESS} / {@code :EVAL}），
 * 按节点分 key 而不是在一个 key 里用 "{@code id:节点}" 拼 member：消费时不需要解析和过滤，
 * 取消时也只是对几个 key 各删一次。
 *
 * <p><b>成员是"租户 ID|工单 ID"而不是光秃秃的工单 ID</b>（阶段 6 第 3 步 · ADR-021）：
 * 定时任务没有登录态，消费时要知道去**哪个库**处理这条任务；而成员是登记当时写下的，
 * 学校切到自己的库之后旧成员里那个库就不对了。存租户 ID、消费时经 {@code TenantSchemaCatalog}
 * 换成当前的库名，切换前后登记的任务就都会落到现在的库上（见 {@link DueTicket}）。
 * 登记与取消都发生在**有租户上下文的请求里**，所以租户 ID 由调用方传进来（不猜、不去会话里摸）。
 *
 * <p>职责边界：本接口只负责"登记到期任务 / 消费到期任务 / 兜底扫描"这些**机制**，
 * 到期后做什么（提醒后勤 / 自动关闭）由调用方（TimeoutScheduler）编排——这样不被
 * TicketService 反向依赖，避免两个 Service 互相引用形成循环依赖。
 */
public interface TimeoutService {

    /** 工单派单后登记未接单提醒：到期时间 = 现在 + 接单阈值（默认 24h）。 */
    void registerAccept(long ticketId, long tenantId);

    /** 显式指定到期时间登记接单提醒（测试与兜底补录用）。 */
    void registerAcceptDeadline(long ticketId, long tenantId, Instant deadline);

    /**
     * 工单进入 30 处理中（接单）后登记未处理升级。
     *
     * <p>基准时间**必须传派单时间**：需求口径是"从派到完工"整体超期（docs/01），
     * 兜底扫描用的也是 `dispatch_time`——两处必须同基准，否则"接了单但拖着不完工"的工单
     * 会在毫秒级路径与分钟级兜底路径上得到不同的到期时刻（这个漂移曾经发生过，见评审）。
     */
    void registerProcess(long ticketId, long tenantId, LocalDateTime dispatchTime);

    /** 显式指定到期时间登记未处理升级（测试与兜底补录用）。 */
    void registerProcessDeadline(long ticketId, long tenantId, Instant deadline);

    /** 工单进入 50 已完成时登记验收超时：到期时间 = 现在 + 验收阈值（默认 24h）。 */
    void registerEval(long ticketId, long tenantId);

    /** 显式指定到期时间登记验收超时（测试与兜底补录用）。 */
    void registerEvalDeadline(long ticketId, long tenantId, Instant deadline);

    /**
     * 取消该工单的**全部**超时登记（节点已完成或到达终态时调用）。
     *
     * <p>会同时清掉"带租户"与"过渡期老格式"两种成员：上线前登记的成员只有工单 ID，
     * 只会清一种的话它们会留到到期为止——虽然消费时因为状态已流转而跳过，但那是白跑一趟。
     */
    void cancel(long ticketId, long tenantId);

    /** 消费到期的接单提醒：ZREM 成功者才进返回列表（多实例唯一消费）。 */
    List<DueTicket> handleDueAccept();

    /** 消费到期的未处理升级：同上。 */
    List<DueTicket> handleDueProcess();

    /** 消费到期的验收超时：同上。 */
    List<DueTicket> handleDueEval();

    /**
     * 兜底：直接扫库找"该提醒接单、却没在 ZSet 里"的工单（Redis 重启丢任务场景）。
     *
     * <p>扫的是**所有库**（平台库 + 每个已搬家的学校库）：一个库一个租户上下文（ADR-021 第 3 步），
     * 返回的每条都带着"该去哪个租户处理"。
     */
    List<DueTicket> backstopScanAccept();

    /** 兜底：直接扫库找"该升级却没在 ZSet 里"的处理中超期工单。 */
    List<DueTicket> backstopScanProcess();

    /** 兜底：直接扫库找"该到期却没在 ZSet 里"的已完成工单。 */
    List<DueTicket> backstopScanEval();
}
