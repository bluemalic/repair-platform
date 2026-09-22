package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 给已有租户新增一个后勤管理员。
 *
 * <p>没有 userType 字段是**故意的**：类型与角色由服务端写死为后勤管理，接口传什么都不影响
 * （照 {@code StudentCreateDTO}）。否则这个接口就能造出平台运营账号。
 *
 * <p><b>为什么需要它</b>：开通租户时只建一个管理员，而学校自己没有任何管理管理员的入口。
 * 那个管理员离职或长期请假后，全校没人能派单、没人能维护账号，而平台只有"开通新租户"这一个动作，
 * 补不上这个洞。
 */
@Data
@Schema(description = "新增租户管理员入参")
public class TenantAdminCreateDTO {

    @Schema(description = "登录名")
    @NotBlank(message = "登录名不能为空")
    @Size(max = 32, message = "登录名最长 32 位")
    private String username;

    @Schema(description = "姓名")
    @Size(max = 32, message = "姓名最长 32 位")
    private String realName;

    @Schema(description = "手机号")
    @Size(max = 20, message = "手机号最长 20 位")
    private String phone;

    @Schema(description = "初始口令，8-32 位；首次登录必须改")
    @NotBlank(message = "初始口令不能为空")
    @Size(min = 8, max = 32, message = "初始口令需为 8-32 位")
    private String password;
}
