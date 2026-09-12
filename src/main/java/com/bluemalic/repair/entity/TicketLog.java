package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单流转日志。追加型（append-only）表：只有 createTime，
 * 写入后不修改、不删除——工单详情的时间线和每一步耗时都从这里出。
 */
@Data
@TableName("ticket_log")
public class TicketLog {

    private Long id;

    private Long tenantId;

    private Long ticketId;

    /** 变更前状态；首次创建（提交）时为 null */
    private Integer fromStatus;

    private Integer toStatus;

    /** 动作，如 DISPATCH / ACCEPT / ARRIVE / FINISH */
    private String action;

    private Long operatorId;

    private String remark;

    private LocalDateTime createTime;
}
