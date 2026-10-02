package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.TicketArriveDTO;
import com.bluemalic.repair.dto.TicketCreateDTO;
import com.bluemalic.repair.dto.TicketEvaluateDTO;
import com.bluemalic.repair.dto.TicketFinishDTO;
import com.bluemalic.repair.dto.TicketRejectDTO;
import com.bluemalic.repair.dto.TicketReworkDTO;
import com.bluemalic.repair.vo.TicketVO;

/**
 * 工单流转域：submit 到 close 的全部状态跃迁（学生侧提交/撤销/评价/打回，
 * 维修工侧接单/到场/完工/驳回，后勤侧驳回/关闭）。从 {@code TicketService} 拆出
 * （纯移动，改动建议 #4 第五步·收官）。
 *
 * <p>查询在 {@link TicketQueryService}、派单在 {@link TicketAssignmentService}、
 * 超时在 {@code TicketTimeoutHandler}。每次流转都经状态机校验（{@code TicketStatus.checkTransition}）、
 * 条件更新落库（受影响行数 0 = 并发改动）、写 {@code ticket_log}、发站内通知——
 * 这些口径收在 {@code TicketTransitionSupport}，四域共用。
 */
public interface TicketFlowService {

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

    /** 后勤关闭（50 → 60），超时自动关闭是 M3 的事，这里是人工兜底。 */
    void close(long id);
}
