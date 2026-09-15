package com.bluemalic.repair.service;

import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.RepairCodeVO;
import com.bluemalic.repair.vo.TicketDetailVO;
import com.bluemalic.repair.vo.TicketVO;
import com.bluemalic.repair.dto.TicketArriveDTO;
import com.bluemalic.repair.dto.TicketCreateDTO;
import com.bluemalic.repair.dto.TicketDispatchDTO;
import com.bluemalic.repair.dto.TicketEvaluateDTO;
import com.bluemalic.repair.dto.TicketFinishDTO;
import com.bluemalic.repair.dto.TicketRejectDTO;

/**
 * 工单业务。所有状态流转都经状态机校验、写 ticket_log、发站内通知。
 * 列表查询的"谁能看到哪些单"由数据权限拦截器统一注入，这里不手写范围条件。
 */
public interface TicketService {

    /** 学生提交报修（可带报修码自动定位楼栋房间）。 */
    TicketVO submit(TicketCreateDTO dto);

    /** 按报修码查位置——学生扫码报修与维修工扫码到场共用（跨端抽象）。 */
    RepairCodeVO byCode(String code);

    /** 当前登录人视角的工单列表（数据范围由拦截器决定）。 */
    PageResult<TicketVO> page(long pageNum, long pageSize, Integer status, Long buildingId, Long categoryId);

    /** 工单详情（含流转时间线与评价）。 */
    TicketDetailVO detail(long id);

    /** 学生撤销（10 → 70）。 */
    void cancel(long id);

    /** 学生验收评价（40 → 50），写 ticket_evaluation，唯一索引兜底幂等。 */
    void evaluate(long id, TicketEvaluateDTO dto);

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
