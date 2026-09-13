package com.bluemalic.repair.controller;

import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.service.TicketService;
import com.bluemalic.repair.vo.RepairCodeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报修码查询——学生扫码报修与维修工扫码到场<b>共用</b>的接口（跨端抽象，AGENTS 第 7 节）。
 * 不为某一端单开接口；路径不在 student / worker / admin 前缀下，只需登录即可调用。
 */
@Tag(name = "报修码", description = "学生扫码报修、维修工扫码到场共用的位置查询")
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketCodeController {

    private final TicketService ticketService;

    @Operation(summary = "按报修码查询位置",
            description = "返回楼栋与房间：学生端用于预填报修表单，维修工端用于比对是否到对了房间")
    @GetMapping("/by-code/{code}")
    public Result<RepairCodeVO> byCode(
            @Parameter(description = "报修码，如 306718") @PathVariable String code) {
        return Result.ok(ticketService.byCode(code));
    }
}
