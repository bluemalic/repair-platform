package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用一次性登录码换 token（阶段 7 · ADR-022）。
 *
 * <p>登录码是回调那一跳带回来的（{@code ?ssoCode=...}），短命且只能用一次。
 * 单独一步交换而不是把 token 放 URL 里：URL 会进浏览器历史、Referer 与代理日志。
 */
@Data
@Schema(description = "统一认证：用登录码换 token")
public class SsoExchangeDTO {

    @Schema(description = "回调跳转地址里带回来的一次性登录码")
    @NotBlank(message = "登录码不能为空")
    @Size(max = 128, message = "登录码格式不对")
    private String code;
}
