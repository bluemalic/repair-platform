package com.bluemalic.repair.common;

import lombok.Getter;

/**
 * 用户类型，与 {@code sys_user.user_type} 的取值一一对应。
 *
 * <p>把"类型码 + 角色码"放在一起，是因为它们在这个项目里是**一对一**的：
 * 学生用 STUDENT 角色、维修工用 WORKER、后勤用 ADMIN。以前这两个值散在各处硬编码
 * （`USER_TYPE_WORKER = 2` 在维修工服务里、演示重置里又写了一遍 `1/2/3`），
 * 改一个漏一个的表现是"账号能登录但每个接口都 403"——很难往常量写错上想。
 *
 * <p>服务端建账号时**类型与角色都由代码指定**，绝不接受前端传入：否则"新增维修工"那个接口
 * 就能造出一个后勤管理员。
 */
@Getter
public enum UserType {

    STUDENT(1, "学生", "STUDENT"),
    WORKER(2, "维修工", "WORKER"),
    ADMIN(3, "后勤管理", "ADMIN");

    private final int code;

    private final String desc;

    /** 对应 {@code sys_role.code}：建账号时要按它写 {@code sys_user_role} 关联。 */
    private final String roleCode;

    UserType(int code, String desc, String roleCode) {
        this.code = code;
        this.desc = desc;
        this.roleCode = roleCode;
    }
}
