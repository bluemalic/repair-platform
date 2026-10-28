package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 这所学校有没有开通统一认证（阶段 7 · ADR-022）。
 *
 * <p>登录页在用户填了学校编码之后问一次，决定要不要显示"统一认证登录"入口。
 * 它只回答"有没有"，**不回任何 URL、不泄露 IdP 配置**（那些配置可能暴露学校内部系统地址）。
 */
@Data
@Schema(description = "学校统一认证的可用性")
public class SsoAvailableVO {

    @Schema(description = "学校名称（登录页可以顺手展示，避免编码填错）")
    private String tenantName;

    @Schema(description = "是否开通并启用了统一认证")
    private Boolean enabled;

    @Schema(description = "协议：OAUTH2 / CAS；未启用时为 null")
    private String protocol;
}
