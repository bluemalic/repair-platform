package com.bluemalic.repair.identity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.entity.WorkerSkill;
import com.bluemalic.repair.identity.api.IdentityQueryApi;
import com.bluemalic.repair.identity.api.UserBriefVO;
import com.bluemalic.repair.identity.api.WorkerScopeVO;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.bluemalic.repair.mapper.WorkerSkillMapper;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * {@link IdentityQueryApi} 的实现（阶段 3.2）。
 *
 * <p>两件事在实现里是刻意的：
 * <ul>
 *   <li><b>按 tenantId 切库</b>：内部调用没有 Sa-Token 会话，{@code TenantRoutingContext} 是空的，
 *       不显式切就会落到平台库、读出一片空——那种"查不到人"最难查；</li>
 *   <li><b>只返回最小字段</b>：{@link UserBriefVO} 里没有口令与手机号，
 *       调用方拿到的就是它该看到的那些。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class IdentityQueryService implements IdentityQueryApi {

    private final SysUserMapper sysUserMapper;

    private final WorkerBuildingMapper workerBuildingMapper;

    private final WorkerSkillMapper workerSkillMapper;

    private final TenantSchemaCatalog catalog;

    @Override
    public UserBriefVO findUser(long tenantId, long userId) {
        SysUser user = TenantRoutingContext.call(catalog.routingKeyFor(tenantId),
                () -> sysUserMapper.selectById(userId));
        return user == null ? null : toBrief(user);
    }

    @Override
    public List<UserBriefVO> findUsers(long tenantId, Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<SysUser> users = TenantRoutingContext.call(catalog.routingKeyFor(tenantId),
                () -> sysUserMapper.selectByIds(ids));
        return users.stream().map(IdentityQueryService::toBrief).toList();
    }

    @Override
    public WorkerScopeVO findWorkerScope(long tenantId, long workerId) {
        String key = catalog.routingKeyFor(tenantId);
        List<Long> buildingIds = TenantRoutingContext.call(key, () -> workerBuildingMapper.selectList(
                        Wrappers.<WorkerBuilding>lambdaQuery().eq(WorkerBuilding::getWorkerId, workerId))
                .stream().map(WorkerBuilding::getBuildingId).toList());
        List<Long> skillIds = TenantRoutingContext.call(key, () -> workerSkillMapper.selectList(
                        Wrappers.<WorkerSkill>lambdaQuery().eq(WorkerSkill::getWorkerId, workerId))
                .stream().map(WorkerSkill::getCategoryId).toList());
        return new WorkerScopeVO(workerId, buildingIds, skillIds);
    }

    @Override
    public boolean existsEnabledAdmin(long tenantId) {
        Long count = TenantRoutingContext.call(catalog.routingKeyFor(tenantId),
                () -> sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getTenantId, tenantId)
                        .eq(SysUser::getUserType, UserType.ADMIN.getCode())
                        .eq(SysUser::getStatus, 1)));
        return count != null && count > 0;
    }

    private static UserBriefVO toBrief(SysUser user) {
        return new UserBriefVO(user.getId(), user.getTenantId(), user.getUsername(),
                user.getRealName(), user.getUserType(), user.getStatus());
    }
}
