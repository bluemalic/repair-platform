package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "修改学生账号入参")
public class StudentUpdateDTO {

    @Schema(description = "姓名")
    @Size(max = 32, message = "姓名最长 32 位")
    private String realName;

    @Schema(description = "手机号")
    @Size(max = 20, message = "手机号最长 20 位")
    private String phone;

    @Schema(description = "状态 1启用 0停用")
    @NotNull(message = "状态不能为空")
    private Integer status;

    /** {@code @Size} 对 null 不做校验，所以"留空不改"和"填了就必须合法"可以共存。 */
    @Schema(description = "口令，留空表示不修改")
    @Size(min = 8, max = 32, message = "口令长度需在 8-32 位之间")
    private String password;
}
