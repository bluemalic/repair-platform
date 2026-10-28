package com.bluemalic.repair.controller;

import com.bluemalic.repair.common.ClientIps;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.LoginDTO;
import com.bluemalic.repair.dto.PasswordChangeDTO;
import com.bluemalic.repair.dto.SsoExchangeDTO;
import com.bluemalic.repair.service.AuthService;
import com.bluemalic.repair.service.SsoService;
import com.bluemalic.repair.vo.CurrentUserVO;
import com.bluemalic.repair.vo.LoginVO;
import com.bluemalic.repair.vo.SsoAvailableVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    private final SsoService ssoService;

    @Operation(summary = "账号密码登录",
            description = "成功后返回 token，前端按 Authorization: Bearer <token> 提交。"
                    + "同一账号按窗口限流，超限返回 10004")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok(authService.login(dto));
    }

    @Operation(summary = "学校是否开通统一认证",
            description = "登录页在用户填了学校编码后调一次，决定要不要显示「统一认证登录」。"
                    + "只回答有没有，不返回任何 IdP 地址")
    @GetMapping("/sso/available")
    public Result<SsoAvailableVO> ssoAvailable(
            @Parameter(description = "学校编码") @RequestParam String tenantCode) {
        return Result.ok(ssoService.available(tenantCode));
    }

    @Operation(summary = "发起统一认证登录",
            description = "返回 302：浏览器跳到学校的身份提供方。`target` = ADMIN / H5，决定登录成功后回到哪个前端")
    @GetMapping("/sso/authorize")
    public ResponseEntity<Void> ssoAuthorize(
            @Parameter(description = "学校编码") @RequestParam String tenantCode,
            @Parameter(description = "登录目标 ADMIN / H5") @RequestParam(required = false) String target,
            HttpServletRequest request) {
        String location = ssoService.authorize(tenantCode, target, ClientIps.of(request));
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }

    @Operation(summary = "统一认证回调",
            description = "身份提供方带 code/ticket 回到这里。成功时 302 回前端并带一次性 `ssoCode`；"
                    + "失败时带 `ssoError` / `ssoMessage`。**token 永远不会出现在 URL 里**")
    @GetMapping("/sso/callback")
    public ResponseEntity<Void> ssoCallback(
            @Parameter(description = "OAuth2 授权码") @RequestParam(required = false) String code,
            @Parameter(description = "CAS ticket（CAS 服务端追加在 service 后面）") @RequestParam(required = false) String ticket,
            @Parameter(description = "一次性 state") @RequestParam(required = false) String state,
            @Parameter(description = "IdP 返回的错误（用户取消等）") @RequestParam(required = false) String error,
            HttpServletRequest request) {
        String location = ssoService.callback(code, ticket, state, error, ClientIps.of(request));
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }

    @Operation(summary = "统一认证：用登录码换 token",
            description = "前端拿到回调里的 `ssoCode` 后立刻调这一步。登录码一次有效、默认 120 秒过期")
    @PostMapping("/sso/exchange")
    public Result<LoginVO> ssoExchange(@Valid @RequestBody SsoExchangeDTO dto, HttpServletRequest request) {
        return Result.ok(ssoService.exchange(dto.getCode(), ClientIps.of(request)));
    }

    @Operation(summary = "登出", description = "注销当前 token")
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok();
    }

    @Operation(summary = "修改密码",
            description = "三端共用。需提供当前密码；改完该账号的所有会话立即失效（含本次），"
                    + "前端必须清本地登录态并跳登录页重新登录")
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody PasswordChangeDTO dto) {
        authService.changePassword(dto);
        return Result.ok();
    }

    @Operation(summary = "当前登录用户",
            description = "含角色码与权限码，供前端做动态路由与按钮级控制")
    @GetMapping("/me")
    public Result<CurrentUserVO> me() {
        return Result.ok(authService.currentUser());
    }
}
