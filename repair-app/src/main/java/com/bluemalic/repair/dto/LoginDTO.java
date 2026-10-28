package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录入参。
 *
 * <p>需要 {@code tenantCode} 是因为用户名（学号 / 工号）只在租户内唯一，
 * 不同学校可能有同一个学号。登录方式为账号密码，不接微信授权登录（见 docs/01）。
 */
@Data
@Schema(description = "登录入参")
public class LoginDTO {

    @Schema(description = "租户编码，如 gdou", example = "gdou")
    @NotBlank(message = "租户编码不能为空")
    private String tenantCode;

    @Schema(description = "登录名（学生用学号、维修工用工号）", example = "20260001")
    @NotBlank(message = "登录名不能为空")
    private String username;

    @Schema(description = "密码")
    @NotBlank(message = "密码不能为空")
    private String password;
}
