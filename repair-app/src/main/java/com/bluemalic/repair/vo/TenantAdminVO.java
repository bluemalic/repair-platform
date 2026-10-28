package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 某个租户的后勤管理员账号。不含 password——任何接口都不返回口令。
 *
 * <p>不复用 {@code StudentVO}：字段确实一样，但那是"学生账号"，出现在平台运营接口的文档里
 * 会让人以为平台能看学生名单——而平台看不到任何租户的业务数据（ADR-012）。
 */
@Data
@Schema(description = "租户的后勤管理员")
public class TenantAdminVO {

    private Long id;

    @Schema(description = "登录名")
    private String username;

    @Schema(description = "姓名")
    private String realName;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "状态 1启用 0停用")
    private Integer status;

    @Schema(description = "是否还在用初始口令（true = 还没激活）")
    private Boolean mustChangePassword;
}
