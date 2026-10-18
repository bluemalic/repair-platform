package com.bluemalic.repair.tenancy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「这个学校的数据在哪个库」——租户目录（阶段 6 · ADR-021）。
 *
 * <p>答案来自平台库的 `tenant.db_schema`：为空 = 还没搬，数据仍在平台库（过渡期的正常状态）；
 * 有值 = 已经在这个学校自己的库里。
 *
 * <p><b>这是全链路唯一一处"把 tenantId 翻译成库名"的地方</b>，也是 fail-closed 的落点：
 * 租户解析不出来（行不存在）就**直接抛错**，绝不回落到平台库。宁可让这一个请求失败并留下日志，
 * 也不能让某所学校的数据被写进另一个库里——后者不会报错，只会等到有人发现数字不对。
 *
 * <p><b>为什么缓存，以及缓存为什么可以没有 TTL</b>：每次请求都要用它（拦截器里），
 * 而它一天也不会变一次。变化只有两个来源：开通学校（本进程自己做的，做完就
 * {@link #refresh}）、搬迁切换（阶段 6 第 3 步，走停写窗口并重启应用）。单实例部署下，
 * 这条缓存的失效是明确的；加了 TTL 反而会让"切换后多久生效"变成一个说不清的问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantSchemaCatalog {

    /** 平台自己在 `tenant` 表里的 id（ADR-012：它不是一所学校） */
    private static final long PLATFORM_TENANT_ID = 0L;

    private final TenantMapper tenantMapper;

    /** tenantId → 路由键（库名或 {@link TenantRoutingContext#CONTROL}） */
    private final Map<Long, String> cache = new ConcurrentHashMap<>();

    /** 本次请求该用哪个库（见类注释：未知租户抛错，不回落）。 */
    public String routingKeyFor(long tenantId) {
        if (tenantId == PLATFORM_TENANT_ID) {
            // 平台账号（tenant_id = 0）：控制面操作与租户管理，永远在平台库
            return TenantRoutingContext.CONTROL;
        }
        String cached = cache.get(tenantId);
        if (cached != null) {
            return cached;
        }
        String resolved = resolve(tenantId);
        cache.put(tenantId, resolved);
        return resolved;
    }

    /** 开通学校 / 搬迁切换之后调用：让下一次请求重新读一次库名。 */
    public void refresh(long tenantId) {
        cache.remove(tenantId);
    }

    /**
     * 已经搬到独立库的学校：tenantId → 库名。启动时给每个库里跑一次迁移要用它
     * （`tenant` 表只在平台库，所以这个读必须钉在平台库上）。
     */
    public Map<Long, String> provisionedSchemas() {
        List<Tenant> tenants = TenantRoutingContext.callControl(() -> tenantMapper.selectList(
                Wrappers.<Tenant>lambdaQuery().isNotNull(Tenant::getDbSchema)));
        Map<Long, String> result = new LinkedHashMap<>();
        for (Tenant tenant : tenants) {
            result.put(tenant.getId(), tenant.getDbSchema());
        }
        return result;
    }

    private String resolve(long tenantId) {
        // 控制面读：这一刻还不知道该去哪个库，或者说"该去哪个库"正是要查的东西——
        // 所以必须显式钉在平台库上（callControl 可嵌套，外层若已经是平台库也无妨）。
        Tenant tenant = TenantRoutingContext.callControl(() -> tenantMapper.selectById(tenantId));
        if (tenant == null) {
            log.error("登录态里的租户在平台库找不到 tenantId={}（账号可能已被停用/删除）", tenantId);
            throw new BizException(ErrorCode.TENANT_NOT_FOUND, "登录状态已失效，请重新登录");
        }
        if (tenant.getDbSchema() == null) {
            // 存量租户：还没搬，数据就在平台库。这是**明确的状态**，不是兜底
            return TenantRoutingContext.CONTROL;
        }
        return tenant.getDbSchema();
    }
}
