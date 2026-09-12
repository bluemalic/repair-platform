package com.bluemalic.repair.controller;

import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.LoginDTO;
import com.bluemalic.repair.service.AuthService;
import com.bluemalic.repair.vo.CurrentUserVO;
import com.bluemalic.repair.vo.LoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口，各端共用（不属于 student / worker / admin 任何一端）。
 */
@Tag(name = "认证", description = "账号密码登录、登出、当前用户")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "账号密码登录",
            description = "成功后返回 token，前端按 Authorization: Bearer <token> 提交")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok(authService.login(dto));
    }

    @Operation(summary = "登出", description = "注销当前 token")
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok();
    }

    @Operation(summary = "当前登录用户",
            description = "含角色码与权限码，供前端做动态路由与按钮级控制")
    @GetMapping("/me")
    public Result<CurrentUserVO> me() {
        return Result.ok(authService.currentUser());
    }
}
