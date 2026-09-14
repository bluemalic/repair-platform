package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.converter.NotificationConverter;
import com.bluemalic.repair.entity.Notification;
import com.bluemalic.repair.mapper.NotificationMapper;
import com.bluemalic.repair.service.NotificationService;
import com.bluemalic.repair.vo.NotificationVO;
import com.bluemalic.repair.vo.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;

    @Override
    public void send(Long tenantId, Long receiverId, String type, String title, String content, Long ticketId) {
        if (receiverId == null) {
            return;
        }
        try {
            Notification notification = new Notification();
            notification.setTenantId(tenantId);
            notification.setReceiverId(receiverId);
            notification.setType(type);
            notification.setTitle(title);
            notification.setContent(content);
            notification.setTicketId(ticketId);
            notification.setIsRead(0);
            notificationMapper.insert(notification);
        } catch (Exception e) {
            log.warn("站内通知发送失败（不影响主流程） receiver={} type={}", receiverId, type, e);
        }
    }

    /**
     * 我的通知（分页）。"只查 receiver_id = 我"由数据权限拦截器注入（handler 的 notification 分支），
     * 这里不手写归属条件——和 ticket 的规则同一个思路：靠自觉会漏，收敛到框架层才默认安全。
     */
    @Override
    public PageResult<NotificationVO> page(long pageNum, long pageSize, Integer isRead) {
        Page<Notification> result = notificationMapper.selectPage(
                new Page<>(clamp(pageNum), clamp(pageSize)),
                Wrappers.<Notification>lambdaQuery()
                        .eq(isRead != null, Notification::getIsRead, isRead)
                        .orderByDesc(Notification::getCreateTime)
                        .orderByDesc(Notification::getId));

        Page<NotificationVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(NotificationConverter::toVO).toList());
        return PageResult.of(voPage);
    }

    /** 未读数。返回 int 而不是 Long：全局 Jackson 规则会把 Long 序列化成字符串，前端角标要数字。 */
    @Override
    public int unreadCount() {
        Long count = notificationMapper.selectCount(
                Wrappers.<Notification>lambdaQuery().eq(Notification::getIsRead, 0));
        return count == null ? 0 : count.intValue();
    }

    /**
     * 标记单条已读。归属校验不写在这里——拦截器会在 UPDATE 的 WHERE 上自动加 receiver_id = 我，
     * 所以"别人的通知 / 不存在的通知"都表现为 rows = 0。
     */
    @Override
    public void markRead(long id) {
        int rows = notificationMapper.update(null, Wrappers.<Notification>lambdaUpdate()
                .eq(Notification::getId, id)
                .set(Notification::getIsRead, 1));
        if (rows == 0) {
            // 对外统一说"不存在"：不告诉尝试者"这条通知存在，只是不是你的"
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "通知不存在");
        }
    }

    /**
     * 全部标记已读。rows = 0 意味着"本来就没有未读"——无事可做，不是错误。
     * （对比工单流转的 rows = 0 必须报错：那里 0 行代表"前提被并发破坏"。）
     */
    @Override
    public void markAllRead() {
        notificationMapper.update(null, Wrappers.<Notification>lambdaUpdate()
                .eq(Notification::getIsRead, 0)
                .set(Notification::getIsRead, 1));
    }

    private long clamp(long value) {
        if (value < 1) {
            return 1;
        }
        return Math.min(value, 100);
    }
}