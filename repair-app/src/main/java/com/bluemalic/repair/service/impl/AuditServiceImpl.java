package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bluemalic.repair.common.AuditAction;
import com.bluemalic.repair.common.Paging;
import com.bluemalic.repair.entity.AuditLog;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.mapper.AuditLogMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.service.AuditService;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.vo.AuditLogVO;
import com.bluemalic.repair.vo.PageResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 操作审计的实现（`docs/01` §4.4）。
 *
 * <p>三条实现上的取舍：
 * <ol>
 *   <li><b>不新开事务</b>：跟随业务事务。业务回滚 → 审计也回滚（"没有记录 = 没有发生"）；
 *       审计失败 → 业务也失败（宁可操作失败，也不要"改了但查不到谁改的"）。</li>
 *   <li><b>名字取当时的快照</b>：操作人姓名从库里读一次（不在登录会话里，会话只存了
 *       mustChangePassword 与 userType），目标名称由调用方传。账号改名或被删之后，
 *       审计记录仍然读得懂。</li>
 *   <li><b>写入前按列宽截断</b>：审计失败会连带业务失败，所以绝不能让"某个名字太长"
 *       把一次正常的业务操作搞挂。</li>
 * </ol>
 *
 * <p>{@code audit_log} 不在数据权限拦截器的名单里，查询**必须显式写 tenant_id**（AGENTS §5.6）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private static final int MAX_OPERATOR_NAME = 50;
    private static final int MAX_TARGET_TYPE = 32;
    private static final int MAX_TARGET_NAME = 100;
    private static final int MAX_DETAIL = 500;
    private static final int MAX_IP = 45;

    private final AuditLogMapper auditLogMapper;

    private final SysUserMapper sysUserMapper;

    private final CurrentTenantService currentTenantService;

    /** 登录成功事件的去重（`docs/01` §4.4）：同一账号 + 同一天 + 同一 IP 只记一条。 */
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    /** 请求对象可能不存在（定时任务等系统上下文）→ 用 ObjectProvider 之外的最轻办法：允许为 null。 */
    private final org.springframework.beans.factory.ObjectProvider<HttpServletRequest> requestProvider;

    @Override
    public void record(String action, String targetType, Long targetId, String targetName, String detail) {
        Long operatorId = operatorId();
        AuditLog entry = new AuditLog();
        // 平台运营的操作落在 tenant_id = 0 上（它本来的租户就是 0）
        entry.setTenantId(currentTenantService.tenantIdOrNull());
        entry.setOperatorId(operatorId == null ? 0L : operatorId);
        entry.setOperatorName(truncate(operatorName(operatorId), MAX_OPERATOR_NAME));
        entry.setAction(action);
        entry.setTargetType(truncate(targetType, MAX_TARGET_TYPE));
        entry.setTargetId(targetId);
        entry.setTargetName(truncate(targetName, MAX_TARGET_NAME));
        entry.setDetail(truncate(detail, MAX_DETAIL));
        entry.setIp(truncate(clientIp(), MAX_IP));
        entry.setCreateTime(LocalDateTime.now());
        auditLogMapper.insert(entry);
    }

    @Override
    public void recordLoginSuccess(long tenantId, long userId, String username) {
        String ip = clientIp();
        if (!firstLoginOfTheDay(tenantId, userId, ip)) {
            return;
        }
        insertLogin(tenantId, userId, username, AuditAction.LOGIN_SUCCESS, "登录成功");
    }

    @Override
    public void recordLoginFailure(long tenantId, Long userId, String username, String action, String detail) {
        insertLogin(tenantId, userId, username, action, detail);
    }

    /**
     * 登录审计的实际写入。**整体包在 try/catch 里**——这两个方法是全项目唯一"审计失败不阻断业务"的地方
     * （理由见接口注释与 `docs/01` §4.4）：登录失败还只是登不进去，登录成功写不进去却要把人挡在门外，
     * 而那时唯一能来修表的管理员也在门外。失败只记 ERROR，不往上抛。
     */
    private void insertLogin(long tenantId, Long userId, String username, String action, String detail) {
        try {
            AuditLog entry = new AuditLog();
            entry.setTenantId(tenantId);
            entry.setOperatorId(userId == null ? 0L : userId);
            entry.setOperatorName(truncate(loginOperatorName(userId, username), MAX_OPERATOR_NAME));
            entry.setAction(action);
            // 登录事件没有"目标"：它没有改任何东西。被尝试的那个账号记在操作人列（见 docs/01 §4.4）
            entry.setDetail(truncate(detail, MAX_DETAIL));
            entry.setIp(truncate(clientIp(), MAX_IP));
            entry.setCreateTime(LocalDateTime.now());
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            log.error("登录审计写入失败（不阻断登录） action={} tenantId={} username={}", action, tenantId, username, e);
        }
    }

    /**
     * 今天这个账号有没有从同一个 IP 登录过（`docs/01` §4.4 的去重规则）。
     *
     * <p>用 Redis 的 SETNX 而不是查库：登录是高频路径（H5 每次冷启动都走），查一次 audit_log
     * 会给这张越来越大的表加一次索引扫描；SETNX 是 O(1)。键带当天日期，TTL 到当天结束——
     * 换句话说"去重只对当天有意义"，跨天自然重新记。
     *
     * <p><b>拿不到 Redis 时返回 true（照记）</b>：宁可多记几条，也不要因为缓存不可用就漏掉登录事件。
     */
    private boolean firstLoginOfTheDay(long tenantId, long userId, String ip) {
        String key = "audit:login:" + tenantId + ":" + userId + ":"
                + LocalDate.now() + ":" + (ip == null ? "-" : ip);
        try {
            Boolean first = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofSeconds(secondsUntilTomorrow()));
            return first == null || first;
        } catch (Exception e) {
            log.warn("登录审计去重键写入失败，按未登录过处理（会多记一条） key={}", key, e);
            return true;
        }
    }

    /** 到明天 0 点还有多少秒（多给 60 秒余量，避免临界点上键提前过期）。 */
    private long secondsUntilTomorrow() {
        LocalDateTime now = LocalDateTime.now();
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).getSeconds() + 60;
    }

    /**
     * 登录记录里的"操作人"：账号存在就用他的姓名，**账号不存在就用尝试时输入的那个用户名**——
     * "有人在试一个不存在的账号"正是这条记录的全部价值。
     */
    private String loginOperatorName(Long userId, String username) {
        if (userId != null) {
            SysUser user = sysUserMapper.selectById(userId);
            if (user != null && StringUtils.hasText(user.getRealName())) {
                return user.getRealName();
            }
        }
        return StringUtils.hasText(username) ? username : "未知账号";
    }

    @Override
    public PageResult<AuditLogVO> page(long pageNum, long pageSize, String action, String operatorKeyword,
                                       LocalDate startDate, LocalDate endDate, boolean includeLogin) {
        long tenantId = currentTenantService.requireTenantId();
        var query = Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getTenantId, tenantId)
                .eq(StringUtils.hasText(action), AuditLog::getAction, action)
                // 没指定动作、也没勾"含登录事件"时把登录类排除掉：这个页面的主查询是"谁改了东西"，
                // 登录记录会占绝大多数（docs/01 §4.4）。**显式指定了动作就不排除**——否则
                // "筛 LOGIN_FAILED 却查不到"会变成一个很难想明白的现象
                .notIn(!StringUtils.hasText(action) && !includeLogin,
                        AuditLog::getAction, AuditAction.LOGIN_ACTIONS)
                .like(StringUtils.hasText(operatorKeyword), AuditLog::getOperatorName, operatorKeyword)
                // 日期筛选用 [startDate 00:00, endDate 次日 00:00) 的半开区间：写成 <= 23:59:59
                // 会丢掉最后一秒里发生的操作，这种边界在排障时最容易被当成"记录丢了"
                .ge(startDate != null, AuditLog::getCreateTime, startDate == null ? null : startDate.atStartOfDay())
                .lt(endDate != null, AuditLog::getCreateTime,
                        endDate == null ? null : endDate.plusDays(1).atStartOfDay())
                .orderByDesc(AuditLog::getCreateTime);
        Page<AuditLog> page = auditLogMapper.selectPage(new Page<>(Paging.clamp(pageNum), Paging.clamp(pageSize)),
                query);

        Page<AuditLogVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).toList());
        return PageResult.of(voPage);
    }

    private AuditLogVO toVO(AuditLog entry) {
        AuditLogVO vo = new AuditLogVO();
        vo.setId(entry.getId());
        vo.setOperatorId(entry.getOperatorId());
        vo.setOperatorName(entry.getOperatorName());
        vo.setAction(entry.getAction());
        // 中文名在服务端拼好：前端不必维护一份动作字典（新动作上线时前端不用跟着发版）
        vo.setActionLabel(AuditAction.label(entry.getAction()));
        vo.setTargetType(entry.getTargetType());
        vo.setTargetId(entry.getTargetId());
        vo.setTargetName(entry.getTargetName());
        vo.setDetail(entry.getDetail());
        vo.setIp(entry.getIp());
        vo.setCreateTime(entry.getCreateTime());
        return vo;
    }

    /** 无登录态（定时任务）返回 null；此时操作人记 0（与 ticket_log 的 SYSTEM_OPERATOR 同一约定）。 */
    private Long operatorId() {
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            return loginId == null ? null : Long.parseLong(String.valueOf(loginId));
        } catch (Exception e) {
            return null;
        }
    }

    private String operatorName(Long operatorId) {
        if (operatorId == null) {
            return "系统";
        }
        SysUser user = sysUserMapper.selectById(operatorId);
        return user == null || !StringUtils.hasText(user.getRealName()) ? String.valueOf(operatorId)
                : user.getRealName();
    }

    /**
     * 来源 IP。取的是 {@code X-Forwarded-For} 的第一段（线上由 nginx 反代，remoteAddr 是容器地址）；
     * 拿不到就不记——审计的价值在"谁 + 改了什么"，IP 只是辅助。
     */
    private String clientIp() {
        HttpServletRequest request = requestProvider.getIfAvailable();
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        log.warn("审计字段超长被截断 length={} max={}", value.length(), max);
        return value.substring(0, max);
    }
}
