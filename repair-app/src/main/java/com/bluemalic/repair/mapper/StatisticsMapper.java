package com.bluemalic.repair.mapper;

import com.bluemalic.repair.vo.StatisticsDistributionVO;
import com.bluemalic.repair.vo.StatisticsOverviewVO;
import com.bluemalic.repair.vo.StatisticsTrendVO;
import com.bluemalic.repair.vo.StatisticsWorkerWorkloadVO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统计看板查询。全部是聚合查询，SQL 写在 {@code resources/mapper/StatisticsMapper.xml}（AGENTS 第 5 节：
 * Service 里不写 SQL）。时间区间统一是左闭右开 {@code [start, end)}，由 Service 把日期补成时间点。
 *
 * <p>注意：这里是自定义 SQL，MyBatis-Plus 的逻辑删除**不会自动生效**，所以 ticket 表的
 * {@code deleted = 0} 必须手写（ticket_log / ticket_evaluation 是追加型表，没有 deleted 列）。
 */
public interface StatisticsMapper {

    StatisticsOverviewVO selectOverview(@Param("tenantId") Long tenantId,
                                        @Param("start") LocalDateTime start,
                                        @Param("end") LocalDateTime end);

    List<StatisticsTrendVO> selectTrendByDay(@Param("tenantId") Long tenantId,
                                             @Param("start") LocalDateTime start,
                                             @Param("end") LocalDateTime end);

    List<StatisticsTrendVO> selectTrendByWeek(@Param("tenantId") Long tenantId,
                                              @Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);

    List<StatisticsDistributionVO> selectDistributionByCategory(@Param("tenantId") Long tenantId,
                                                                @Param("start") LocalDateTime start,
                                                                @Param("end") LocalDateTime end);

    List<StatisticsDistributionVO> selectDistributionByBuilding(@Param("tenantId") Long tenantId,
                                                                @Param("start") LocalDateTime start,
                                                                @Param("end") LocalDateTime end);

    List<StatisticsDistributionVO> selectDistributionByUrgency(@Param("tenantId") Long tenantId,
                                                               @Param("start") LocalDateTime start,
                                                               @Param("end") LocalDateTime end);

    List<StatisticsWorkerWorkloadVO> selectWorkerWorkload(@Param("tenantId") Long tenantId,
                                                          @Param("start") LocalDateTime start,
                                                          @Param("end") LocalDateTime end);
}