package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增维修工入参。
 *
 * <p><b>没有 userType 字段，是故意的</b>：这个接口只能造维修工，用户类型由服务端写死。
 * 一旦让前端传，同一个接口就能造出后勤管理员——权限提升的入口往往就是这么来的。
 *
 * <p>初始密码由管理员设置并当面告知（项目明确不做短信 / 邮件通道，服务端随机生成也一样要靠人传达，
 * 反而多一步）。密码只在写入时用一次，任何接口都不会把它读回来。
 */
@Data
@Schema(description = "新增维修工入参")
public class WorkerCreateDTO {

    @Schema(description = "工号，同时作为登录名（租户内唯一）", example = "W2026001")
    @NotBlank(message = "工号不能为空")
    @Size(max = 32, message = "工号最长 32 位")
    private String username;

    @Schema(description = "姓名", example = "李师傅")
    @NotBlank(message = "姓名不能为空")
    @Size(max = 32, message = "姓名最长 32 位")
    private String realName;

    @Schema(description = "手机号（可选）")
    @Size(max = 20, message = "手机号最长 20 位")
    private String phone;

    @Schema(description = "初始密码，8-32 位")
    @NotBlank(message = "初始密码不能为空")
    @Size(min = 8, max = 32, message = "密码长度需在 8-32 位之间")
    private String password;
}
