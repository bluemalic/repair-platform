package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 登录成功的出参。注意**不含密码**——实体绝不直接当接口出参（AGENTS 第 5 节）。
 */
@Data
@Schema(description = "登录结果")
public class LoginVO {

    @Schema(description = "token 的请求头名，目前是 Authorization")
    private String tokenName;

    @Schema(description = "token 值，前端按 Authorization: Bearer <值> 提交")
    private String tokenValue;

    private Long userId;

    private String username;

    private String realName;

    @Schema(description = "1学生 2维修工 3后勤管理")
    private Integer userType;
}
