package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.TenantSsoConfigDTO;
import com.bluemalic.repair.vo.TenantSsoConfigVO;

/**
 * 平台运营维护"某所学校的统一认证配置"（阶段 7 · ADR-022）。
 *
 * <p>配置只在**平台库**：登录入口在"知道该去哪个租户库"之前就要读它（ADR-021）。
 * 平台运营是唯一能改它的人（`tenant:manage`），学校侧看不到 IdP 地址与密钥。
 */
public interface TenantSsoConfigService {

    /** 读配置；从未配置过时返回 {@code configured=false} 的默认值（前端好渲染表单）。 */
    TenantSsoConfigVO get(long tenantId);

    /**
     * 保存配置（有就改、没有就建）。
     *
     * <p>{@code clientSecret} 留空表示**不修改**；协议相关的必填项在服务层按 `protocol` 校验。
     * 写审计（动作码 {@code TENANT_SSO_UPDATE}），摘要里**绝不出现密钥**。
     */
    TenantSsoConfigVO update(long tenantId, TenantSsoConfigDTO dto);
}
