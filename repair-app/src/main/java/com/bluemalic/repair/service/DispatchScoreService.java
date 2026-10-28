package com.bluemalic.repair.service;

import com.bluemalic.repair.vo.DispatchCandidateVO;

import java.util.List;

/**
 * 派单候选评分（阶段 3 · ADR-019）。
 *
 * <p><b>只排序，不派单</b>：它给后勤一份"谁更合适 + 为什么"的名单，派单仍走
 * {@link TicketAssignmentService#dispatch}，由人点确认。这与 Agent/分诊同一条哲学——
 * 系统给建议、人做决定；自动派单在"师傅请假 / 临时抽调"时会把简单问题变成要撤销的烂账
 * （`docs/01` 的"明确不做"已经写明）。
 */
public interface DispatchScoreService {

    /**
     * 给一张工单算候选人排名。
     *
     * <p>工单本身走数据权限拦截器（`ticket` 在覆盖名单里）：**看不到这张单的人拿不到候选**，
     * 对外口径与工单详情一致（查不到就是"不存在"）。
     */
    List<DispatchCandidateVO> candidates(long ticketId);
}
