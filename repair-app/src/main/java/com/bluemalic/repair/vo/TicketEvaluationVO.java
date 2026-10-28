package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "验收评价")
public class TicketEvaluationVO {

    private Long id;

    private Integer score;

    private String content;

    private LocalDateTime createTime;
}
