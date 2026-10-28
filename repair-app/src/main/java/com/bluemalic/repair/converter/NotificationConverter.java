package com.bluemalic.repair.converter;

import com.bluemalic.repair.entity.Notification;
import com.bluemalic.repair.vo.NotificationVO;

/**
 * Notification 实体 → NotificationVO 的转换。
 *
 * <p><b>为什么要有这一层，而不是把 entity 直接返回给前端：</b>
 * <ul>
 *   <li>entity 是数据库的镜子（字段跟着 DDL 走），带 {@code tenantId} 这类内部字段。
 *       直接返回等于<b>把表结构当成 API 契约</b>——以后表里加个内部字段，就意外暴露给前端了。
 *       VO 的字段只由"前端需要什么"决定，两者变化的原因不同，所以要有一层翻译。</li>
 *   <li>转换收敛到这里是<b>唯一入口</b>：手写 getter/setter 在字段改名时<b>编译期就报错</b>；
 *       而 {@code BeanUtils.copyProperties} 这类反射复制会静默漏掉（名字对不上不报错，只是值是 null），
 *       又是一种"静默失败"。</li>
 * </ul>
 *
 * <p>通知是纯 1:1 字段复制，不需要 {@link TicketConverter} 那种"批量查表补楼栋名/类别名"的逻辑
 * ——那类需要业务逻辑的转换才值得写复杂，这里保持最简形式即可。
 */
public class NotificationConverter {

    /** 工具类，禁止实例化。 */
    private NotificationConverter() {
    }

    public static NotificationVO toVO(Notification notification) {
        if (notification == null) {
            return null;
        }
        NotificationVO vo = new NotificationVO();
        vo.setId(notification.getId());
        vo.setType(notification.getType());
        vo.setTitle(notification.getTitle());
        vo.setContent(notification.getContent());
        vo.setTicketId(notification.getTicketId());
        vo.setIsRead(notification.getIsRead());
        vo.setCreateTime(notification.getCreateTime());
        return vo;
    }
}
