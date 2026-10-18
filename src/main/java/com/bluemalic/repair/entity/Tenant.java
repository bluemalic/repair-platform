package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 租户（学校 / 校区）。所有业务表都带 tenantId，这是多租户的基础。 */
@Data
@TableName("tenant")
public class Tenant {

    private Long id;

    private String name;

    /** 租户编码，登录时用于定位租户，全局唯一 */
    private String code;

    /**
     * 租户库名（阶段 6 · ADR-021）。NULL = 存量租户，数据仍在平台库。
     *
     * <p>它是**路由的依据**：请求进来自登录态拿到 tenantId，再经这张表换成库名，
     * 决定这次请求用哪个连接池。所以它只在平台库里，租户库里没有这张表。
     */
    private String dbSchema;

    private String contact;

    private String phone;

    /** 学生账号上限；0 = 不限（阶段 5 · ADR-020） */
    private Integer maxStudents;

    /** 维修工账号上限；0 = 不限 */
    private Integer maxWorkers;

    private Integer status;

    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
