package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.StatisticsQueryDTO;
import com.bluemalic.repair.vo.StatisticsDistributionVO;
import com.bluemalic.repair.vo.StatisticsOverviewVO;
import com.bluemalic.repair.vo.StatisticsTrendVO;
import com.bluemalic.repair.vo.StatisticsWorkerWorkloadVO;

import java.util.List;

/**
 * 统计看板（M3）。数据范围 = 当前登录后勤管理员所属租户；时间范围缺省为"近 30 天"。
 *
 * <p>"超时"口径统一取自 ticket_log 里的超时动作记录（ACCEPT_TIMEOUT / PROCESS_TIMEOUT / AUTO_CLOSE），
 * 不在 SQL 里重算阈值——阈值可配置，两处实现会悄悄漂移。
 */
public interface StatisticsService {

    StatisticsOverviewVO overview(StatisticsQueryDTO query);

    List<StatisticsTrendVO> trend(StatisticsQueryDTO query);

    List<StatisticsDistributionVO> distribution(StatisticsQueryDTO query);

    List<StatisticsWorkerWorkloadVO> workerWorkload(StatisticsQueryDTO query);
}