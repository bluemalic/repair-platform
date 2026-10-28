package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改维修工入参（不含工号：工号是他的登录名，改它等于换一个账号）。
 *
 * <p>密码**留空即不改**，填了就是管理员帮师傅重置——否则师傅忘了密码只能改库。
 * 停用（status=0）会同时让该师傅已登录的 token 失效，见 {@code WorkerServiceImpl.update}。
 */
@Data
@Schema(description = "修改维修工入参")
public class WorkerUpdateDTO {

    @Schema(description = "姓名", example = "李师傅")
    @NotBlank(message = "姓名不能为空")
    @Size(max = 32, message = "姓名最长 32 位")
    private String realName;

    @Schema(description = "手机号（可选）")
    @Size(max = 20, message = "手机号最长 20 位")
    private String phone;

    @Schema(description = "状态 1启用 0停用；停用会让该师傅已登录的 token 立即失效")
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 1启用 / 0停用")
    @Max(value = 1, message = "状态只能是 1启用 / 0停用")
    private Integer status;

    /** {@code @Size} 对 null 不做校验，所以"留空不改"和"填了就必须合法"可以共存。 */
    @Schema(description = "新密码，8-32 位；留空表示不修改")
    @Size(min = 8, max = 32, message = "密码长度需在 8-32 位之间")
    private String password;
}
