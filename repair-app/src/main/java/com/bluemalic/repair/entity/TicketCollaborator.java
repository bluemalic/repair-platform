package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单协作者（`docs/01` §4.5）。**主责不在这张表里**——他仍然是 {@code ticket.worker_id}；
 * "谁是这单的责任人"必须一眼可见，不能变成"查这张表里哪一行特殊"。
 *
 * <p>纯关联表：没有 {@code update_time} / {@code deleted}（与 {@code worker_building} 同一形态）。
 * 移除一个协作者就是删掉这行；"谁在什么时候加入、谁移除了谁"记在 {@code ticket_log} 里。
 */
@Data
@TableName("ticket_collaborator")
public class TicketCollaborator {

    private Long id;

    private Long tenantId;

    private Long ticketId;

    /** 协作维修工ID */
    private Long workerId;

    private LocalDateTime createTime;
}
