package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 报修类别。租户内的字典数据，{@code defaultUrgency} 让常见类别自带默认紧急度。 */
@Data
@TableName("ticket_category")
public class TicketCategory {

    private Long id;

    private Long tenantId;

    private String name;

    /** 默认紧急度 1普通 2紧急 3特急 */
    private Integer defaultUrgency;

    private Integer sort;

    private Integer status;

    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
