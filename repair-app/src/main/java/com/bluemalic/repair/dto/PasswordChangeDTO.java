package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 自助改密入参。三端共用（学生 / 维修工 / 后勤都改自己的密码）。
 *
 * <p>没有"确认新密码"字段：那是前端防手滑的交互，服务端存的是同一个值，
 * 收两遍不增加任何安全性，反而多一处"两个字段不一致"的校验分支。
 */
@Data
@Schema(description = "修改密码入参")
public class PasswordChangeDTO {

    @Schema(description = "当前密码")
    @NotBlank(message = "当前密码不能为空")
    private String oldPassword;

    @Schema(description = "新密码，8-32 位")
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 32, message = "新密码长度需为 8-32 位")
    private String newPassword;
}
