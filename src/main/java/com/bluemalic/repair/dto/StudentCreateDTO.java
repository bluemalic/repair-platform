package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "新增学生账号入参")
public class StudentCreateDTO {

    @Schema(description = "学号（登录名）", example = "20260003")
    @NotBlank(message = "学号不能为空")
    @Size(max = 32, message = "学号最长 32 位")
    private String username;

    @Schema(description = "姓名")
    @Size(max = 32, message = "姓名最长 32 位")
    private String realName;

    @Schema(description = "手机号")
    @Size(max = 20, message = "手机号最长 20 位")
    private String phone;

    /**
     * 没有 userType 字段是**故意的**：类型与角色由服务端写死为学生，
     * 接口传什么都不影响。否则这个接口就能造出后勤管理员。
     */
    @Schema(description = "初始口令，8-32 位")
    @NotBlank(message = "初始口令不能为空")
    @Size(min = 8, max = 32, message = "初始口令需为 8-32 位")
    private String password;
}
