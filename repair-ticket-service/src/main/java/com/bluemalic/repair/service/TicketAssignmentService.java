package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.TicketCollaboratorDTO;
import com.bluemalic.repair.dto.TicketDispatchDTO;
import com.bluemalic.repair.dto.TicketMergeDTO;
import com.bluemalic.repair.dto.TicketSplitDTO;
import com.bluemalic.repair.dto.TicketTransferDTO;
import com.bluemalic.repair.vo.TicketVO;

/**
 * 工单派单域：派单、转派、协作者管理、拆单。
 *
 * <p>五类动作都由后勤触发（数据范围 = 本租户）；目标校验与跨楼栋留痕在
 * {@code TicketAssignmentPolicy}，流转写入/日志/Outbox 在 {@code TicketTransitionSupport}，
 * 通知在 {@code TicketNoticePublisher}。
 */
public interface TicketAssignmentService {

    /** 后勤派单（10/45/80 → 20）；已驳回或"无法维修"的工单可重新派单，上一轮的接单/到场时间清空。 */
    void dispatch(long id, TicketDispatchDTO dto);

    /**
     * 转派：把已派出去的单换个人做（20 / 30 → 20），`docs/01` §4.1。
     *
     * <p>与驳回、打回都不同：单不用退回调度池，师傅也不停手——只是换了个人。
     * 计时**从头开始**（`dispatch_time` 重置、上一轮的接单/到场时间清空），
     * 不能让新师傅背前一个人的延迟。
     */
    void transfer(long id, TicketTransferDTO dto);

    /**
     * 后勤恢复处理（35 待料 / 45 无法维修 → 30），`docs/01` §4.7。
     *
     * <p>45 进来时处理超时节点已经撤掉；恢复时按**原派单时间**重新登记——超期就立即升级，
     * 不能因为"等后勤拍板"把计时清零。
     */
    void resume(long id);

    /**
     * 合并重复工单（10/20/30/35/40/45/80 → 75），`docs/01` §4.7。
     *
     * <p>把当前单并进 `targetId` 指向的那张单：当前单变成终态 75、记下 `merged_into_id`、
     * 清空维修工并撤掉超时节点；目标单**不动**。被合并单的学生会收到一条带目标工单号的通知。
     */
    void merge(long id, TicketMergeDTO dto);

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
}
