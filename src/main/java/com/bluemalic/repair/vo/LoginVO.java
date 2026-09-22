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

    /**
     * 是否必须先去改口令。true 表示这个账号还在用管理员设的初始口令——
     * 前端应该直接跳到改密页，而不是进主页；后端也会在拦截器里挡住其它接口。
     *
     * <p>为什么不止让前端判断：前端的跳转是"引导"，绕过它照样能调接口。
     * 首登强制改密要真的成立，必须在服务端也拦一道。
     */
    @Schema(description = "是否需先修改初始口令")
    private Boolean mustChangePassword;
}
