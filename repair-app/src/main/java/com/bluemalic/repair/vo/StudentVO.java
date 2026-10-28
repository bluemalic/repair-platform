package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 学生账号。不含 password——任何接口都不返回口令。 */
@Data
@Schema(description = "学生账号")
public class StudentVO {

    private Long id;

    @Schema(description = "学号（登录名）")
    private String username;

    @Schema(description = "姓名")
    private String realName;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "状态 1启用 0停用")
    private Integer status;

    /**
     * 是否还在用初始口令（首次登录必须改密）。
     * 列表里显示出来，管理员一眼能看到"这批学生还没激活"。
     */
    @Schema(description = "是否需强制改密（true = 还在用初始口令）")
    private Boolean mustChangePassword;
}
