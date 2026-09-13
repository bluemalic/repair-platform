package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.entity.Notification;
import com.bluemalic.repair.mapper.NotificationMapper;
import com.bluemalic.repair.service.NotificationService;
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
}
