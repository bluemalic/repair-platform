package com.bluemalic.repair.controller;

import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.service.LocationService;
import com.bluemalic.repair.vo.LocationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 可报修的位置（docs/03 §5.1，跨端共用）。
 *
 * <p>学生手动选位置时用它——**公共区域没有码可扫**，不可能为了报修去满楼找码（docs/01 §4.6）。
 * 师傅到场打卡查位置仍走 `by-code`：两条入口共用同一套报修码。
 *
 * <p>登录即可访问，不配权限码：它只是"本校有哪些位置"，与 `/api/categories` 同一口径；
 * 返回内容严格按登录态里的租户过滤（`tenant_id` 显式写，本表不在拦截器名单里）。
 */
@Tag(name = "公共-位置选择", description = "学生报修时手选位置；只返回有报修码的位置")
@RestController
@RequestMapping("/api/locations")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    @Operation(summary = "可报修的位置",
            description = "按楼栋分组：宿舍房间（locationType=1）与公共区域（locationType=2）。"
                    + "**只返回有报修码的位置**——到场核验要靠那张码，没码的位置报了也验不了到场")
    @GetMapping
    public Result<List<LocationVO>> list() {
        return Result.ok(locationService.list());
    }
}
