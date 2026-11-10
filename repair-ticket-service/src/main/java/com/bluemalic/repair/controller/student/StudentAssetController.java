package com.bluemalic.repair.controller.student;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.service.TicketQueryService;
import com.bluemalic.repair.vo.AssetOptionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学生端 - 房间内可关联的设备（阶段 3 · docs/01 §4.8）。
 *
 * <p>只按「楼栋 + 房间」列，不做按设备编号查：学生已经通过报修码/手动选位置定位到房间，
 * 让他从这间房的设备里挑一台，比再扫一张设备码少一步，也不会把设备码和报修码混在一起。
 */
@Tag(name = "学生端-设备", description = "报修时可选关联的房间设备")
@SaCheckPermission("ticket:create")
@RestController
@RequestMapping("/api/student/assets")
@RequiredArgsConstructor
public class StudentAssetController {

    private final TicketQueryService ticketQueryService;

    @Operation(summary = "房间内可关联的设备", description = "只返回本租户、未停用/报废的设备；房间为空时返回空数组")
    @GetMapping
    public Result<List<AssetOptionVO>> list(
            @Parameter(description = "楼栋ID") @RequestParam long buildingId,
            @Parameter(description = "房间/位置，如 201") @RequestParam String room) {
        return Result.ok(ticketQueryService.assetsForLocation(buildingId, room));
    }
}
