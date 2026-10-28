package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "通知记录")
public class NotificationVO {

    private Long id;

    @Schema(description = "通知类型，如 TICKET_DISPATCHED / TICKET_ACCEPTED")
    private String type;

    private String title;

    private String content;

    @Schema(description = "关联工单ID，无关联工单时为 null")
    private Long ticketId;

    @Schema(description = "是否已读 0未读 1已读")
    private Integer isRead;

    private LocalDateTime createTime;

}
