package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 角色-权限关联。纯关联表，只有三个字段（理由见 docs/02）。 */
@Data
@TableName("sys_role_permission")
public class SysRolePermission {

    private Long id;

    private Long roleId;

    private Long permissionId;
}
