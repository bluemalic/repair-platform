package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户-角色关联。纯关联表，只有三个字段：
 * 不带审计字段和逻辑删除——解除授权就是删行，没有"删了还要查历史"的场景（见 docs/02）。
 */
@Data
@TableName("sys_user_role")
public class SysUserRole {

    private Long id;

    private Long userId;

    private Long roleId;
}
