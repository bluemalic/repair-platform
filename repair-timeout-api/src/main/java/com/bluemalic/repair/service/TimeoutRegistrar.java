package com.bluemalic.repair.service;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 超时的**登记端口**（阶段 3.4 · ADR-035）：工单命令路径只需要它。
 *
 * <p>为什么把它从 {@code TimeoutService} 里拆出来：登记是工单域的事（派单后登记、完工后取消），
 * 而扫描/消费/执行是超时域的事。两者放在一个接口里，工单模块就必须依赖超时模块，
 * 而超时模块又依赖工单的流转内核——环。
 *
 * <p><b>登记发生在有租户上下文的请求里</b>，所以租户 ID 由调用方传进来（不猜、不去会话里摸）。
 * 登记是幂等的：重复登记同一个工单只会覆盖到期时间（ZSet 的同一 member）。
 */
public interface TimeoutRegistrar {

    /** 工单派单后登记未接单提醒：到期时间 = 现在 + 接单阈值（默认 24h）。 */
    void registerAccept(long ticketId, long tenantId);

    /** 显式指定到期时间登记接单提醒（测试与兜底补录用）。 */
    void registerAcceptDeadline(long ticketId, long tenantId, Instant deadline);

    /**
     * 工单进入 30 处理中（接单）后登记未处理升级。
     *
     * <p>基准时间**必须传派单时间**：需求口径是"从派到完工"整体超期（docs/01），
     * 兜底扫描用的也是 {@code dispatch_time}——两处必须同基准，否则"接了单但拖着不完工"的工单
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
}
