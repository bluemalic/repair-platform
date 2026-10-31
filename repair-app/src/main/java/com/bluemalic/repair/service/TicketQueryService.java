package com.bluemalic.repair.service;

import com.bluemalic.repair.common.WorkerTaskScope;
import com.bluemalic.repair.vo.AssetOptionVO;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.RepairCodeVO;
import com.bluemalic.repair.vo.TicketDetailVO;
import com.bluemalic.repair.vo.TicketVO;

import java.util.List;

/**
 * 工单查询域（只读）：报修码定位、学生列表、维修工任务、详情、房间内可选设备。
 *
 * <p>校验与取名复用 {@code TicketReferenceValidator} / {@code TicketReadModelAssembler}，
 * 读取入口复用 {@code TicketLookup}；不要在本域重复实现这些口径。
 *
 * <p>可见范围由数据权限拦截器注入（学生=本人 / 维修工=负责楼栋或派给我的单 / 后勤=本租户），
 * 实现里只写"这个视图要看什么"的业务筛选。
 */
public interface TicketQueryService {

    /**
     * 按报修码查询位置——学生扫码报修与维修工扫码到场<b>共用</b>的接口（跨端抽象，AGENTS 第 7 节）。
     */
    RepairCodeVO byCode(String code);

    /**
     * 工单分页。学生=本人的单、维修工=负责楼栋或派给我的单、后勤=本租户全部——数据范围由拦截器保证。
     */
    PageResult<TicketVO> page(long pageNum, long pageSize, Integer status, Long buildingId, Long categoryId);

    /**
     * 维修工的任务列表：「我的任务」（派给我的 + 我协作的）与「本楼栋」两个视图。
     */
    PageResult<TicketVO> pageWorkerTasks(long pageNum, long pageSize, Integer status, WorkerTaskScope scope);

    /**
     * 工单详情：位置与报修信息、流转时间线、维修结果、评价、协作者名单。
     */
    TicketDetailVO detail(long id);

    /** 某个房间下可关联的设备（学生提交时选填）；只返回本租户未停用的。 */
    List<AssetOptionVO> assetsForLocation(long buildingId, String room);
}
