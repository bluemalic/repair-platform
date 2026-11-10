package com.bluemalic.repair.controller;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.identity.api.IdentityQueryApi;
import com.bluemalic.repair.identity.api.UserBriefVO;
import com.bluemalic.repair.identity.api.WorkerScopeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务间只读接口（阶段 3.2 · {@code docs/03 §4.1.1}）。
 *
 * <p><b>路径刻意不落在 {@code /api/**} 下</b>：网关只路由 {@code /api/**} 与文档路径，
 * 所以 {@code /internal/**} 从公网根本到不了；服务也不 publish 端口，只有内网可达。
 * 这是一层结构性的隔离，不是靠"记得加白名单"。
 *
 * <p><b>第二层是 token</b>：{@code repair.identity.internal-token}（环境变量 {@code INTERNAL_TOKEN}）
 * 为空时这些端点**直接 404**——没配就等于没开，不会留下一个裸的"按 tenantId 查用户"接口。
 * 配了但请求头对不上返回 10002。
 */
@Tag(name = "内部-身份只读接口", description = "仅服务间调用：ticket 域回填姓名、校验派单范围、平台开通自检")
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalIdentityController {

    private final IdentityQueryApi identityQueryApi;

    @Value("${repair.identity.internal-token:}")
    private String internalToken;

    @Operation(summary = "按 ID 取单个用户")
    @GetMapping("/users/{userId}")
    public Result<UserBriefVO> findUser(@PathVariable long userId,
                                        @RequestParam long tenantId,
                                        @RequestParam(required = false) String token) {
        requireEnabled(token);
        return Result.ok(identityQueryApi.findUser(tenantId, userId));
    }

    @Operation(summary = "批量取用户（详情页 / 列表回填姓名）")
    @GetMapping("/users")
    public Result<List<UserBriefVO>> findUsers(@RequestParam long tenantId,
                                               @RequestParam List<Long> ids,
                                               @RequestParam(required = false) String token) {
        requireEnabled(token);
        return Result.ok(identityQueryApi.findUsers(tenantId, ids));
    }

    @Operation(summary = "维修工的负责楼栋与擅长类别")
    @GetMapping("/workers/{workerId}/scope")
    public Result<WorkerScopeVO> workerScope(@PathVariable long workerId,
                                             @RequestParam long tenantId,
                                             @RequestParam(required = false) String token) {
        requireEnabled(token);
        return Result.ok(identityQueryApi.findWorkerScope(tenantId, workerId));
    }

    @Operation(summary = "这所学校是否还有启用中的管理员")
    @GetMapping("/tenants/{tenantId}/has-enabled-admin")
    public Result<Boolean> hasEnabledAdmin(@PathVariable long tenantId,
                                           @RequestParam(required = false) String token) {
        requireEnabled(token);
        return Result.ok(identityQueryApi.existsEnabledAdmin(tenantId));
    }

    /**
     * token 为空：端点按"不存在"处理（404），不暴露它的存在；
     * 配了但请求头不对：按未认证处理（10002），给出可排查的信号。
     */
    private void requireEnabled(String provided) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!internalToken.equals(provided)) {
            throw new BizException(ErrorCode.NOT_LOGIN);
        }
    }
}
