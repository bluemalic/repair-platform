package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色。{@code tenantId = 0} 表示平台内置角色（所有租户共用），
 * 例如 STUDENT / WORKER / ADMIN。
 */
@Data
@TableName("sys_role")
public class SysRole {

    private Long id;

    /** 0 = 平台内置角色 */
    private Long tenantId;

    /** 角色编码，如 STUDENT / WORKER / ADMIN */
    private String code;

    private String name;

    private String description;

    private Integer status;

    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
