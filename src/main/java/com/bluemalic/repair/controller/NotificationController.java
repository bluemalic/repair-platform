package com.bluemalic.repair.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.service.NotificationService;
import com.bluemalic.repair.vo.NotificationVO;
import com.bluemalic.repair.vo.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "通知", description = "通知管理（三端共用，数据范围 = 只看自己的）")
@SaCheckPermission("notification:read")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "我的通知", description = "分页返回当前用户的通知；isRead 可筛 0未读 / 1已读，不传为全部")
    @GetMapping
    public Result<PageResult<NotificationVO>> page(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) Integer isRead) {
        return Result.ok(notificationService.page(pageNum, pageSize, isRead));
    }

    @Operation(summary = "未读数", description = "返回数字而非字符串——前端角标直接用它")
    @GetMapping("/unread-count")
    public Result<Integer> unreadCount() {
        return Result.ok(notificationService.unreadCount());
    }

    @Operation(summary = "标记已读", description = "不是自己的通知表现为 10006 不存在")
    @PutMapping("/{id}/read")
    public Result<Void> markRead(@Parameter(description = "通知ID") @PathVariable long id) {
        notificationService.markRead(id);
        return Result.ok();
    }

    @Operation(summary = "全部已读", description = "把当前用户所有未读标为已读；本来就没有未读也算成功")
    @PutMapping("/read-all")
    public Result<Void> markAllRead() {
        notificationService.markAllRead();
        return Result.ok();
    }
}



