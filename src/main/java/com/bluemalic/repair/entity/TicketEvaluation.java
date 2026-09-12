package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单验收评价。追加型表，提交后不允许修改（避免"先差评再改好评"），
 * 表上有 uk_ticket 唯一索引作为评价幂等的兜底。
 */
@Data
@TableName("ticket_evaluation")
public class TicketEvaluation {

    private Long id;

    private Long tenantId;

    private Long ticketId;

    private Long studentId;

    /** 评分 1-5 */
    private Integer score;

    private String content;

    private LocalDateTime createTime;
}
