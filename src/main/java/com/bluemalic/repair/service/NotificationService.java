package com.bluemalic.repair.service;

import com.bluemalic.repair.vo.NotificationVO;
import com.bluemalic.repair.vo.PageResult;

/**
 * 站内通知。工单状态变更时给相关方插一条，前端轮询未读数。
 *
 * <p>发送失败只记日志、不抛异常——通知是旁路，不能让主流程因它失败（docs/03 §7.2）。
 */
public interface NotificationService {

    void send(Long tenantId, Long receiverId, String type, String title, String content, Long ticketId);

    PageResult<NotificationVO> page(long pageNum, long pageSize, Integer isRead);

    int unreadCount();

    void markRead(long id);

    void markAllRead();
}
