package com.bluemalic.repair.service;

import com.bluemalic.repair.vo.TicketVO;
import com.bluemalic.repair.dto.TicketArriveDTO;
import com.bluemalic.repair.dto.TicketCollaboratorDTO;
import com.bluemalic.repair.dto.TicketCreateDTO;
import com.bluemalic.repair.dto.TicketDispatchDTO;
import com.bluemalic.repair.dto.TicketEvaluateDTO;
import com.bluemalic.repair.dto.TicketFinishDTO;
import com.bluemalic.repair.dto.TicketRejectDTO;
import com.bluemalic.repair.dto.TicketReworkDTO;
import com.bluemalic.repair.dto.TicketSplitDTO;
import com.bluemalic.repair.dto.TicketTransferDTO;

/**
 * 工单业务。所有状态流转都经状态机校验、写 ticket_log、发站内通知。
 * 列表查询的"谁能看到哪些单"由数据权限拦截器统一注入，这里不手写范围条件。
 */
public interface TicketService {

    /** 学生提交报修（可带报修码自动定位楼栋房间）。 */
    TicketVO submit(TicketCreateDTO dto);

    /** 学生撤销（10 → 70）。 */
    void cancel(long id);

    /** 学生验收评价（40 → 50），写 ticket_evaluation，唯一索引兜底幂等。 */
    void evaluate(long id, TicketEvaluateDTO dto);

    /**
     * 学生验收不通过（40 → 30，打回重做），`docs/01` §4.1。
     *
     * <p>与"驳回"是两件事：驳回清空派单退回调度池（这单不该我做），打回**保留 `worker_id`**
     * （还是你做，但没做好）。理由进 `ticket_log` 并通知维修工。
     */
    void rework(long id, TicketReworkDTO dto);

    /**
     * 转派：把已派出去的单换个人做（20 / 30 → 20），`docs/01` §4.1。
     *
     * <p>与驳回、打回都不同：单不用退回调度池，师傅也不停手——只是换了个人。
     * 计时**从头开始**（`dispatch_time` 重置、上一轮的接单/到场时间清空），
     * 不能让新师傅背前一个人的延迟。
     */
    void transfer(long id, TicketTransferDTO dto);

    /**
     * 加协作者（`docs/01` §4.5）：一件活要两个人干时，后勤把另一个师傅拉进来。
     *
     * <p>20 待接单 / 30 处理中才能加（10 还没主责、40 之后活已经干完），最多 3 个；
     * 协作者**能到场、能完工**，不能接单 / 驳回 / 转派。上限、可见性与"谁完工算完工"的规则都在 §4.5。
     */
    void addCollaborator(long id, TicketCollaboratorDTO dto);

    /** 移除协作者。被移除后随之不可见——留痕在 `ticket_log`，不是无声无息。 */
    void removeCollaborator(long id, long workerId);

    /**
     * 拆单：原单里其实是两件事时，拆出一张**新的待派单工单**（10）并返回它。
     *
     * <p>新单继承原单的楼栋 / 房间 / 学生 / 现场图片，描述另填；**只拆一层**（拆出来的单不能再拆）；
     * 不在这里指定师傅——"派给谁"是独立的一个动作（派单），理由见 `docs/01` §4.5。
     */
    TicketVO split(long id, TicketSplitDTO dto);

    /** 维修工接单（20 → 30），条件更新兜住并发抢单。 */
    void accept(long id);

    /** 维修工到场打卡（30 → 30，只记时间），校验报修码与工单位置一致。 */
    void arrive(long id, TicketArriveDTO dto);

    /** 维修工完工上报（30 → 40）。 */
    void finish(long id, TicketFinishDTO dto);

    /** 维修工驳回（20/30 → 80）。 */
    void rejectByWorker(long id, TicketRejectDTO dto);

    /** 后勤驳回（20/30/40 → 80）。 */
    void rejectByAdmin(long id, TicketRejectDTO dto);

    /** 后勤派单（10/80 → 20）；已驳回的工单可重新派单。 */
    void dispatch(long id, TicketDispatchDTO dto);

    /** 后勤关闭（50 → 60），超时自动关闭是 M3 的事，这里是人工兜底。 */
    void close(long id);

    /** 超时自动关闭（50 → 60）。仅超时调度器调用（系统上下文，操作者=0），不对外暴露接口。 */
    void autoClose(long id);

    /**
     * 接单超时提醒：20 待接单 超过阈值（默认 24h）仍无人接单时，提醒本租户后勤管理员。
     * 仅超时调度器调用；不是状态跃迁（20 → 20，只写 ticket_log + 通知）。
     *
     * <p><b>职责边界</b>：是否"已到期"由调用方判定（ZSet 按 score、兜底扫描按时间阈值），
     * 本方法只判定"还该不该提醒"——状态仍是 20，且 ticket_log 里没有 ACCEPT_TIMEOUT 记录
     * （幂等：兜底扫描每分钟都会扫到同一批超期工单，不判重就会把管理员刷屏）。
     */
    void remindAcceptTimeout(long id);

    /**
     * 处理超时升级：30 处理中 自派单起超过阈值（默认 48h）仍未完工时，升级提醒本租户后勤管理员。
     * 仅超时调度器调用；同样不是状态跃迁（30 → 30，只写 ticket_log + 通知）。
     *
     * <p>职责边界与 {@link #remindAcceptTimeout} 一致：是否到期由调用方判定（ZSet score / 兜底扫描阈值），
     * 本方法只判定"还该不该升级"——状态仍是 30，且 ticket_log 里没有 PROCESS_TIMEOUT 记录。
     */
    void escalateProcessTimeout(long id);
}
