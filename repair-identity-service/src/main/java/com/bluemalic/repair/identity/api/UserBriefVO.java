package com.bluemalic.repair.identity.api;

/**
 * 跨服务用的"人的最小画像"（阶段 3.2）。
 *
 * <p>只带展示与判断需要的字段：**没有 password、没有 phone**。ticket 域要的是"这个 id 叫什么、
 * 是不是还在职"，多给一列就多一处泄露面。
 */
public record UserBriefVO(
        Long id,
        Long tenantId,
        String username,
        String realName,
        Integer userType,
        Integer status) {
}
