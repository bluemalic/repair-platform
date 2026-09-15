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

    /**
     * 发给该租户的全部启用中后勤管理员（角色 code = ADMIN）——"提醒调度方"用的接收者解析。
     * 放在通知模块里，是为了让业务层不必自己拼 sys_role / sys_user_role / sys_user 三张表。
     * 返回实际送达人数（0 表示该租户没有可用管理员，调用方按需记日志）。
     */
    int sendToTenantAdmins(Long tenantId, String type, String title, String content, Long ticketId);

    PageResult<NotificationVO> page(long pageNum, long pageSize, Integer isRead);

    int unreadCount();

    void markRead(long id);

    void markAllRead();
}
