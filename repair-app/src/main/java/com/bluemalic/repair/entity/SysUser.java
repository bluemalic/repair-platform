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

    /**
     * 是否需强制改密：0否 1是。管理员建的账号（学生批量导入、维修工新增）为 1，
     * 改密成功后由 {@code AuthServiceImpl.changePassword} 置 0。
     *
     * <p>存在的原因：初始口令只能是**统一**的（要写在纸上发给学生，一人一个没人记得住），
     * 而学号在班里是公开的——统一口令意味着同学之间可以互相登录。所以它必须是一次性的。
     */
    private Integer mustChangePassword;

    /** 逻辑删除 0否 1是。名字必须叫 deleted，全局配置按这个名字识别，改名会导致逻辑删除失效 */
    private Integer deleted;

    /** 由数据库的 DEFAULT CURRENT_TIMESTAMP 填充，代码里不要赋值 */
    private LocalDateTime createTime;

    /** 由数据库的 ON UPDATE CURRENT_TIMESTAMP 维护，代码里不要赋值 */
    private LocalDateTime updateTime;
}
