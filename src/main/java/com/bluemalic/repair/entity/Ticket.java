package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单主表（核心表，27 列）。
 *
 * <p>{@code autoResultMap = true} 必须开着：{@code images} / {@code resultImages} 是 json 列，
 * 只有开启它，TypeHandler 才会在**查询**时生效。这一点很容易漏——插入不受影响，
 * 只测"能存进去"是发现不了问题的，必须查回来才算验证过。
 */
@Data
@TableName(value = "ticket", autoResultMap = true)
public class Ticket {

    private Long id;

    private Long tenantId;

    /** 工单号，展示用，如 WX20260910001 */
    private String ticketNo;

    private Long studentId;

    /** 未派单时为 null */
    private Long workerId;

    private Long buildingId;

    private String room;

    private Long categoryId;

    private String description;

    /** 现场图片 URL 数组 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> images;

    /** 1普通 2紧急 3特急 */
    private Integer urgency;

    /** 10待派单 20待接单 30处理中 40待验收 50已完成 60已关闭 70已撤单 80已驳回 */
    private Integer status;

    /** 派单方式 1手动 2自动 */
    private Integer dispatchType;

    private String rejectReason;

    private String resultDesc;

    /** 维修后照片 URL 数组 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> resultImages;

    private LocalDateTime submitTime;

    private LocalDateTime dispatchTime;

    private LocalDateTime acceptTime;

    /** 到场时间（维修工扫报修码打卡） */
    private LocalDateTime arriveTime;

    private LocalDateTime finishTime;

    private LocalDateTime closeTime;

    /** 响应时长(分钟) = 到场时间 − 派单时间，冗余字段 */
    private Integer arriveMinutes;

    /** 处理时长(分钟) = 完工时间 − 到场时间，冗余字段 */
    private Integer handleMinutes;

    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
