package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户。学生 / 维修工 / 后勤管理三种角色共用这一张表，靠 {@code userType} 区分
 * （不拆三张表的理由见 docs/02-数据库设计.md）。
 *
 * <p>字段名必须与 {@code sys_user} 的列名严格对应（下划线转驼峰由全局配置负责）。
 * 这里刻意不写 {@code @TableId} 和 {@code @TableLogic}：雪花 ID 与逻辑删除字段已在
 * application.yml 的全局配置里统一声明，重复写反而多一处可能写歪的地方。
 */
@Data
@TableName("sys_user")
public class SysUser {

    private Long id;

    private Long tenantId;

    private String username;

    private String password;

    private String realName;

    private String phone;

    /** 1学生 2维修工 3后勤管理 */
    private Integer userType;

    /** 1启用 0停用 */
    private Integer status;

    /** 逻辑删除 0否 1是。名字必须叫 deleted，全局配置按这个名字识别，改名会导致逻辑删除失效 */
    private Integer deleted;

    /** 由数据库的 DEFAULT CURRENT_TIMESTAMP 填充，代码里不要赋值 */
    private LocalDateTime createTime;

    /** 由数据库的 ON UPDATE CURRENT_TIMESTAMP 维护，代码里不要赋值 */
    private LocalDateTime updateTime;
}
