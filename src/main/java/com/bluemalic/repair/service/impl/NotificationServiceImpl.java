package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.Paging;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.converter.NotificationConverter;
import com.bluemalic.repair.entity.Notification;
import com.bluemalic.repair.entity.SysRole;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.mapper.NotificationMapper;
import com.bluemalic.repair.mapper.SysRoleMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.service.NotificationService;
import com.bluemalic.repair.vo.NotificationVO;
import com.bluemalic.repair.vo.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    /** 后勤管理员的角色码（schema.sql 种子：sys_role 3 = ADMIN）。 */
    private static final String ROLE_ADMIN = "ADMIN";

    private final NotificationMapper notificationMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysUserMapper sysUserMapper;

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

    @Override
    public int sendToTenantAdmins(Long tenantId, String type, String title, String content, Long ticketId) {
        List<Long> adminIds = tenantAdminIds(tenantId);
        if (adminIds.isEmpty()) {
            // 不静默：超时提醒没人收时，日志要能看出来是"没有管理员"而不是"没触发"
            log.warn("该租户没有启用中的后勤管理员，通知未送达 tenantId={} type={}", tenantId, type);
            return 0;
        }
        adminIds.forEach(adminId -> send(tenantId, adminId, type, title, content, ticketId));
        return adminIds.size();
    }

    /**
     * 角色码 ADMIN → 该角色下的用户 → 过滤出本租户且启用中的。三次单表查询，比 JOIN 更好读。
     *
     * <p>用 {@code in(roleIds)} 而不是按 code 取单条：{@code sys_role} 的唯一键是
     * {@code (tenant_id, code)}，平台内置角色（tenant_id=0）与租户自定义同码角色可以并存，
     * 按 code 查单条会撞出多行。
     */
    private List<Long> tenantAdminIds(Long tenantId) {
        List<Long> adminRoleIds = sysRoleMapper.selectList(
                        Wrappers.<SysRole>lambdaQuery().eq(SysRole::getCode, ROLE_ADMIN))
                .stream().map(SysRole::getId).toList();
        if (adminRoleIds.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = sysUserRoleMapper.selectList(
                        Wrappers.<SysUserRole>lambdaQuery().in(SysUserRole::getRoleId, adminRoleIds))
                .stream().map(SysUserRole::getUserId).distinct().toList();
        if (userIds.isEmpty()) {
            return List.of();
        }
        return sysUserMapper.selectByIds(userIds).stream()
                .filter(user -> tenantId.equals(user.getTenantId()))
                .filter(user -> Integer.valueOf(1).equals(user.getStatus()))
                .map(SysUser::getId)
                .toList();
    }

    /**
     * 我的通知（分页）。"只查 receiver_id = 我"由数据权限拦截器注入（handler 的 notification 分支），
     * 这里不手写归属条件——和 ticket 的规则同一个思路：靠自觉会漏，收敛到框架层才默认安全。
     */
    @Override
    public PageResult<NotificationVO> page(long pageNum, long pageSize, Integer isRead) {
        Page<Notification> result = notificationMapper.selectPage(
                new Page<>(Paging.clamp(pageNum), Paging.clamp(pageSize)),
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

}