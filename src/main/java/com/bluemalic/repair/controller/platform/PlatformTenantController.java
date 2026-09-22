package com.bluemalic.repair.controller.platform;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.TenantAdminCreateDTO;
import com.bluemalic.repair.dto.TenantAdminPasswordDTO;
import com.bluemalic.repair.dto.TenantCreateDTO;
import com.bluemalic.repair.dto.TenantStatusDTO;
import com.bluemalic.repair.dto.TenantUpdateDTO;
import com.bluemalic.repair.service.TenantProvisionService;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.TenantAdminVO;
import com.bluemalic.repair.vo.TenantVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台运营端 - 租户管理（docs/03 §5.5）。
 *
 * <p><b>这是一个与三个业务端并列的第四个接口域</b>，路径前缀 {@code /api/platform/**}。
 * 它不属于任何租户：调用者是平台运营账号（{@code tenant_id = 0}），权限码只有
 * {@code tenant:manage} 这一个。
 *
 * <p>平台看不到任何租户的业务数据（工单 / 学生 / 统计）——这条不是靠"这里没写那些接口"，
 * 而是靠 {@code PlatformScopeInterceptor} 把平台账号关在这个前缀里：它调任何
 * {@code /api/admin/**}、{@code /api/student/**}、{@code /api/worker/**} 都会拿到 10003，
 * 包括那几个"只要登录就能调"的接口（{@code /api/categories} 等）。
 */
@Tag(name = "平台运营端-租户管理",
        description = "开通 / 停用学校，维护每所学校的后勤管理员。平台账号看不到任何学校的业务数据")
@SaCheckPermission("tenant:manage")
@RestController
@RequestMapping("/api/platform/tenants")
@RequiredArgsConstructor
public class PlatformTenantController {

    private final TenantProvisionService tenantProvisionService;

    @Operation(summary = "租户列表",
            description = "分页；可按状态筛选、按学校名称或编码模糊搜索。**不含平台自身**（它不是学校）")
    @GetMapping
    public Result<PageResult<TenantVO>> page(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") long pageNum,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") long pageSize,
            @Parameter(description = "状态筛选 1启用 0停用") @RequestParam(required = false) Integer status,
            @Parameter(description = "学校名称或编码关键字") @RequestParam(required = false) String keyword) {
        return Result.ok(tenantProvisionService.page(pageNum, pageSize, status, keyword));
    }

    @Operation(summary = "开通学校",
            description = "建租户 + 建它的第一个后勤管理员（同一个事务）。"
                    + "管理员账号一律带「首次登录必须改密」：初始口令是平台告诉学校的一次性凭证")
    @PostMapping
    public Result<TenantVO> create(@Valid @RequestBody TenantCreateDTO dto) {
        return Result.ok(tenantProvisionService.provision(dto));
    }

    @Operation(summary = "修改学校资料",
            description = "改名称 / 联系人 / 电话。编码不给改——它是师生天天输入的登录参数")
    @PutMapping("/{id}")
    public Result<Void> update(@Parameter(description = "租户ID") @PathVariable long id,
                               @Valid @RequestBody TenantUpdateDTO dto) {
        tenantProvisionService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "启用 / 停用学校",
            description = "**停用会把该校所有在线用户立即踢下线**，之后他们也无法再登录（登录接口要求租户启用中）")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@Parameter(description = "租户ID") @PathVariable long id,
                                     @Valid @RequestBody TenantStatusDTO dto) {
        tenantProvisionService.changeStatus(id, dto.getStatus());
        return Result.ok();
    }

    @Operation(summary = "学校的后勤管理员列表",
            description = "含「是否还在用初始口令」，平台一眼能看出哪所学校还没激活")
    @GetMapping("/{id}/admins")
    public Result<List<TenantAdminVO>> admins(@Parameter(description = "租户ID") @PathVariable long id) {
        return Result.ok(tenantProvisionService.admins(id));
    }

    @Operation(summary = "新增学校的管理员",
            description = "给已有学校再加一个后勤管理员（不新建学校）。"
                    + "用途：学校只有一个管理员，那人离职或长期请假后没人能派单、没人能维护账号")
    @PostMapping("/{id}/admins")
    public Result<TenantAdminVO> addAdmin(@Parameter(description = "租户ID") @PathVariable long id,
                                          @Valid @RequestBody TenantAdminCreateDTO dto) {
        return Result.ok(tenantProvisionService.addAdmin(id, dto));
    }

    @Operation(summary = "重置管理员口令",
            description = "后勤管理员忘记口令时的唯一出路（项目不做短信 / 邮件，没有自助找回）。"
                    + "重置后该账号首次登录必须改密")
    @PutMapping("/{id}/admins/{userId}/password")
    public Result<Void> resetAdminPassword(@Parameter(description = "租户ID") @PathVariable long id,
                                           @Parameter(description = "管理员用户ID") @PathVariable long userId,
                                           @Valid @RequestBody TenantAdminPasswordDTO dto) {
        tenantProvisionService.resetAdminPassword(id, userId, dto.getPassword());
        return Result.ok();
    }
}
