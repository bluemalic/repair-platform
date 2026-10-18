package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 学校统一认证配置（阶段 7 · ADR-022）——**出参永不含 `clientSecret`**。
 *
 * <p>密钥只回答"配没配"（{@code clientSecretConfigured}）：平台运营改配置时不需要看到它，
 * 看到了反而有泄露面（浏览器缓存、截图、日志）。
 */
@Data
@Schema(description = "学校统一认证配置（不含密钥）")
public class TenantSsoConfigVO {

    private Long tenantId;

    @Schema(description = "是否已经配置过（从未配置过时为 false，其余字段为默认值）")
    private Boolean configured;

    private Boolean enabled;

    @Schema(description = "OAUTH2 / CAS")
    private String protocol;

    private String authorizeUrl;

    private String tokenUrl;

    private String userinfoUrl;

    private String clientId;

    @Schema(description = "密钥是否已配置（不回显密钥本身）")
    private Boolean clientSecretConfigured;

    private String scope;

    private String casLoginUrl;

    private String casValidateUrl;

    private String usernameAttribute;

    private String adminRedirectUrl;

    private String h5RedirectUrl;
}
