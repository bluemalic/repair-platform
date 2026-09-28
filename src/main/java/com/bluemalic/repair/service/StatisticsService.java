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

    /**
     * 导出统计报表（Excel，P1）：四张工作表对应上面四个方法，**取数走的就是它们**——
     * 导出不复制口径，所以 Excel 里的数与页面上的数不可能对不上（口径漂移在这个模块里踩过，
     * 防线就只有一条：不写第二份实现）。
     *
     * <p>返回字节数组而不是写出到流：报表是聚合结果（几百行、几十 KB），先落内存换来两件事——
     * ① 校验失败（如时间范围超 366 天）时一个字节都没写，错误照常走统一异常处理器回 JSON；
     * ② 响应带得上 {@code Content-Length}。代价与"什么时候必须改成直写响应流"写在
     * {@code StatisticsExcelWriter} 的类注释里。
     */
    byte[] export(StatisticsQueryDTO query);
}