package com.bluemalic.repair.mapper;

import com.bluemalic.repair.vo.WorkerScoreStatVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 派单评分的批量统计（阶段 3 · ADR-019）。
 *
 * <p>为什么不加在 `TicketMapper` 上：它是**一次跨工单与评价的聚合**，和工单的增删改查不是一类事；
 * 单独一个 Mapper 能让"哪些查询是给评分用的"一眼可见（`StatisticsMapper` 也是同样的分工）。
 */
public interface DispatchMapper {

    /**
     * 一次取回每位候选人的"进行中工单数"与"历史平均评分"。
     *
     * @param workerIds 非空（调用方在空列表时直接返回，不查库）
     */
    List<WorkerScoreStatVO> loadStats(@Param("tenantId") long tenantId,
                                      @Param("workerIds") List<Long> workerIds);
}
