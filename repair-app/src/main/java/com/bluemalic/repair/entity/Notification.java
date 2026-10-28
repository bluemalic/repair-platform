package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notification")
public class Notification {

    private Long id;

    private Long tenantId;

    private Long receiverId;

    private String type;

    private String title;

    private String content;

    private Long ticketId;

    private Integer isRead;

    private LocalDateTime createTime;
}
