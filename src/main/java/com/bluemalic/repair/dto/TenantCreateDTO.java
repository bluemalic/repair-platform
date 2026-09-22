package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 开通一所学校：建租户 + 建它的第一个后勤管理员。
 *
 * <p><b>为什么这两件事在一个接口里</b>：只建出租户、没有管理员，那个租户就是个谁也进不去的空壳
 * ——平台只能再调一次"加管理员"才能用。开通是一个动作，就让它一次做完（同一个事务）。
 *
 * <p><b>管理员的口令由平台运营设置</b>，并且账号一律带"首次登录必须改密"：这个口令是平台方
 * 口头/微信告诉学校的，属于一次性凭证（与 ADR-011 里学生账号同一条理由）。
 */
@Data
@Schema(description = "开通租户入参（含首个后勤管理员）")
public class TenantCreateDTO {

    @Schema(description = "学校名称", example = "广东海洋大学")
    @NotBlank(message = "学校名称不能为空")
    @Size(max = 64, message = "学校名称最长 64 位")
    private String name;

    /**
     * 编码是登录参数的一部分（登录要填"学校编码"），所以限定在小写字母/数字/连字符：
     * 学校名可以随便改，编码会被师生天天输入，不允许出现容易打错的东西（空格、大写、中文）。
     */
    @Schema(description = "学校编码（登录用，2-32 位小写字母/数字/连字符）", example = "gdou")
    @NotBlank(message = "学校编码不能为空")
    @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,31}$",
            message = "学校编码只能是 2-32 位小写字母、数字或连字符，且不能以连字符开头")
    private String code;

    @Schema(description = "联系人")
    @Size(max = 32, message = "联系人最长 32 位")
    private String contact;

    @Schema(description = "联系电话")
    @Size(max = 20, message = "联系电话最长 20 位")
    private String phone;

    @Schema(description = "管理员登录名")
    @NotBlank(message = "管理员登录名不能为空")
    @Size(max = 32, message = "管理员登录名最长 32 位")
    private String adminUsername;

    @Schema(description = "管理员姓名")
    @Size(max = 32, message = "管理员姓名最长 32 位")
    private String adminRealName;

    @Schema(description = "管理员手机号")
    @Size(max = 20, message = "管理员手机号最长 20 位")
    private String adminPhone;

    @Schema(description = "管理员初始口令，8-32 位；首次登录必须改")
    @NotBlank(message = "管理员初始口令不能为空")
    @Size(min = 8, max = 32, message = "管理员初始口令需为 8-32 位")
    private String adminPassword;
}
