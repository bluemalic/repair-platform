package com.bluemalic.repair.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学校统一身份认证（SSO）配置（阶段 7 · ADR-022）。
 *
 * <p>一所学校一套，主键就是 `tenant_id`。它只在**平台库**：登录时还不知道该去哪个租户库，
 * 必须先按学校编码在平台库读到这份配置。
 *
 * <p>实体本身带 `clientSecret`，但**接口出参永远不带它**（`TenantSsoConfigVO` 只回"是否已配置"）。
 */
@Data
@TableName("tenant_sso_config")
public class TenantSsoConfig {

    @TableId
    private Long tenantId;

    private Integer enabled;

    /** OAUTH2 / CAS */
    private String protocol;

    private String authorizeUrl;

    private String tokenUrl;

    private String userinfoUrl;

    private String clientId;

    private String clientSecret;

    private String scope;

    private String casLoginUrl;

    private String casValidateUrl;

    private String usernameAttribute;

    private String adminRedirectUrl;

    private String h5RedirectUrl;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 是否启用。**不要叫 {@code isEnabled()}**：实体里已有 Lombok 生成的 {@code getEnabled()}（Integer），
     * 两个 getter 同名不同型会让 MyBatis 的反射直接报 "Illegal overloaded getter"（踩过）。
     * 这里用 record 风格的 {@code enabled()}，刻意避开 JavaBean 命名。
     */
    public boolean enabled() {
        return Integer.valueOf(1).equals(enabled);
    }
}
