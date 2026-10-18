package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 平台运营配置某所学校的统一认证（阶段 7 · ADR-022）。
 *
 * <p>只做"格式"校验；**协议相关的必填项**（OAuth2 要三个地址 + clientId，CAS 要两个地址）
 * 在服务层判断——那要看 `protocol` 的值，注解表达不了"按另一个字段分支"。
 */
@Data
@Schema(description = "学校统一认证配置")
public class TenantSsoConfigDTO {

    @Schema(description = "是否启用")
    @Min(value = 0, message = "enabled 只能是 0 或 1")
    @Max(value = 1, message = "enabled 只能是 0 或 1")
    private Integer enabled;

    @Schema(description = "协议 OAUTH2 / CAS", example = "OAUTH2")
    @Pattern(regexp = "OAUTH2|CAS", message = "协议只支持 OAUTH2 / CAS")
    private String protocol;

    // ---------- OAuth2 ----------

    @Schema(description = "OAuth2 授权地址（浏览器跳转，必须外网可达）")
    @Pattern(regexp = "^https?://.+", message = "授权地址必须是 http(s) URL")
    @Size(max = 255, message = "授权地址最长 255 位")
    private String authorizeUrl;

    @Schema(description = "OAuth2 换 token 地址（服务端调用）")
    @Pattern(regexp = "^https?://.+", message = "token 地址必须是 http(s) URL")
    @Size(max = 255, message = "token 地址最长 255 位")
    private String tokenUrl;

    @Schema(description = "OAuth2 用户信息地址（服务端调用）")
    @Pattern(regexp = "^https?://.+", message = "用户信息地址必须是 http(s) URL")
    @Size(max = 255, message = "用户信息地址最长 255 位")
    private String userinfoUrl;

    @Schema(description = "OAuth2 客户端 ID")
    @Size(max = 128, message = "clientId 最长 128 位")
    private String clientId;

    /**
     * 留空 = **保持原值不变**（平台运营改别的字段时不必重新输入密钥）。
     * 响应里永远不回显它，只回"是否已配置"。
     */
    @Schema(description = "OAuth2 客户端密钥；留空表示不修改")
    @Size(max = 255, message = "clientSecret 最长 255 位")
    private String clientSecret;

    @Schema(description = "OAuth2 scope，留空用 openid profile")
    @Size(max = 64, message = "scope 最长 64 位")
    private String scope;

    // ---------- CAS ----------

    @Schema(description = "CAS 登录地址（浏览器跳转）")
    @Pattern(regexp = "^https?://.+", message = "CAS 登录地址必须是 http(s) URL")
    @Size(max = 255, message = "CAS 登录地址最长 255 位")
    private String casLoginUrl;

    @Schema(description = "CAS ticket 校验地址（服务端调用）")
    @Pattern(regexp = "^https?://.+", message = "CAS 校验地址必须是 http(s) URL")
    @Size(max = 255, message = "CAS 校验地址最长 255 位")
    private String casValidateUrl;

    // ---------- 身份映射与落点 ----------

    @Schema(description = "IdP 返回的哪个属性是登录名（学号/工号）", example = "username")
    @Pattern(regexp = "^[A-Za-z0-9_.-]{1,64}$", message = "登录名字段只允许字母/数字/下划线/点/连字符")
    private String usernameAttribute;

    @Schema(description = "登录成功后跳回管理端的地址（只允许用这里配的，防开放重定向）")
    @Pattern(regexp = "^https?://.+", message = "管理端跳转地址必须是 http(s) URL")
    @Size(max = 255, message = "管理端跳转地址最长 255 位")
    private String adminRedirectUrl;

    @Schema(description = "登录成功后跳回 H5 的地址")
    @Pattern(regexp = "^https?://.+", message = "H5 跳转地址必须是 http(s) URL")
    @Size(max = 255, message = "H5 跳转地址最长 255 位")
    private String h5RedirectUrl;
}
