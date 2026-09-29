package com.bluemalic.repair.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.TicketCollaboratorDTO;
import com.bluemalic.repair.dto.TicketDispatchDTO;
import com.bluemalic.repair.dto.TicketRejectDTO;
import com.bluemalic.repair.dto.TicketSplitDTO;
import com.bluemalic.repair.dto.TicketTransferDTO;
import com.bluemalic.repair.service.TicketService;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.TicketDetailVO;
import com.bluemalic.repair.vo.TicketVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后勤管理端工单接口。数据范围 = 本租户全部工单。
 */
@Tag(name = "后勤管理端-工单")
@RestController
@RequestMapping("/api/admin/tickets")
@RequiredArgsConstructor
public class AdminTicketController {

    private final TicketService ticketService;

    @Operation(summary = "全部工单", description = "支持状态 / 楼栋 / 类别筛选")
    @SaCheckPermission("ticket:list:all")
    @GetMapping
    public Result<PageResult<TicketVO>> page(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") long pageNum,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") long pageSize,
            @Parameter(description = "状态筛选") @RequestParam(required = false) Integer status,
            @Parameter(description = "楼栋筛选") @RequestParam(required = false) Long buildingId,
            @Parameter(description = "类别筛选") @RequestParam(required = false) Long categoryId) {
        return Result.ok(ticketService.page(pageNum, pageSize, status, buildingId, categoryId));
    }

    @Operation(summary = "工单详情", description = "含流转时间线与评价")
    @SaCheckPermission("ticket:list:all")
    @GetMapping("/{id}")
    public Result<TicketDetailVO> detail(@Parameter(description = "工单ID") @PathVariable long id) {
        return Result.ok(ticketService.detail(id));
    }

    @Operation(summary = "派单", description = "待派单/已驳回 → 待接单。**已有维修工的单不能走这里**：换人用转派")
    @SaCheckPermission("ticket:dispatch")
    @PostMapping("/{id}/dispatch")
    public Result<Void> dispatch(@Parameter(description = "工单ID") @PathVariable long id,
                                 @Valid @RequestBody TicketDispatchDTO dto) {
        ticketService.dispatch(id, dto);
        return Result.ok();
    }

    /**
     * 转派：换个人做，单不退（`docs/01` §4.1）。
     *
     * <p>理由必填——这条动作会让原师傅手上的活突然消失，他得知道为什么（"临时有事"和"你做得不行"是两回事）。
     * 计时会从头开始（`dispatch_time` 重置、上一轮的接单/到场时间清空），不能让新师傅背前一个人的延迟。
     */
    @Operation(summary = "转派",
            description = "待接单/处理中 → 待接单（换人）。理由必填，会随通知发给原师傅。"
                    + "计时重置、上一轮接单/到场时间清空；不能转给当前维修工")
    @SaCheckPermission("ticket:transfer")
    @PostMapping("/{id}/transfer")
    public Result<Void> transfer(@Parameter(description = "工单ID") @PathVariable long id,
                                 @Valid @RequestBody TicketTransferDTO dto) {
        ticketService.transfer(id, dto);
        return Result.ok();
    }

    @Operation(summary = "驳回", description = "待接单/处理中/待验收 → 已驳回")
    @SaCheckPermission("ticket:reject")
    @PostMapping("/{id}/reject")
    public Result<Void> reject(@Parameter(description = "工单ID") @PathVariable long id,
                               @Valid @RequestBody TicketRejectDTO dto) {
        ticketService.rejectByAdmin(id, dto);
        return Result.ok();
    }

    @Operation(summary = "关闭工单", description = "已完成 → 已关闭；超时自动关闭在 M3，这里是人工兜底")
    @SaCheckPermission("ticket:close")
    @PostMapping("/{id}/close")
    public Result<Void> close(@Parameter(description = "工单ID") @PathVariable long id) {
        ticketService.close(id);
        return Result.ok();
    }

    @Operation(summary = "加协作者（多人同做一单）",
            description = "把另一个师傅拉进这单一起干（docs/01 §4.5）。待接单/处理中才能加，最多 3 人；"
                    + "协作者能到场、能完工，不能接单/驳回/转派。权限与派单同源：都是「谁参与这单」的判断")
    @SaCheckPermission("ticket:dispatch")
    @PostMapping("/{id}/collaborators")
    public Result<Void> addCollaborator(@Parameter(description = "工单ID") @PathVariable long id,
                                       @Valid @RequestBody TicketCollaboratorDTO dto) {
        ticketService.addCollaborator(id, dto);
        return Result.ok();
    }

    @Operation(summary = "移除协作者",
            description = "被移除的师傅随之看不到这单（留痕在工单时间线里）。状态门槛与加协作者一致")
    @SaCheckPermission("ticket:dispatch")
    @DeleteMapping("/{id}/collaborators/{workerId}")
    public Result<Void> removeCollaborator(@Parameter(description = "工单ID") @PathVariable long id,
                                           @Parameter(description = "维修工ID") @PathVariable long workerId) {
        ticketService.removeCollaborator(id, workerId);
        return Result.ok();
    }

    @Operation(summary = "拆单",
            description = "原单里其实是两件事时，拆出一张**待派单的新工单**（docs/01 §4.5）：继承楼栋/房间/学生/"
                    + "现场图片，描述另填，类别与紧急度可改。只拆一层；不在这里指定师傅——派单是独立动作。"
                    + "返回新工单，供前端直接显示新单号")
    @SaCheckPermission("ticket:dispatch")
    @PostMapping("/{id}/split")
    public Result<TicketVO> split(@Parameter(description = "工单ID") @PathVariable long id,
                                  @Valid @RequestBody TicketSplitDTO dto) {
        return Result.ok(ticketService.split(id, dto));
    }
}
