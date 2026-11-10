package com.bluemalic.repair.tenancy;

import java.util.List;

/**
 * 把"只读账号的授权"铺到一组库上——**SPI**，实现留在用到 AI 的那个服务里。
 *
 * <p>为什么要有这层接口：建库与迁移（{@link TenantDatabaseProvisioner}）属于租户基础设施，
 * 任何服务都可能调用；而"给 AI 只读账号授权"是 ai 服务的事。直接依赖会把 common 拖向 ai
 * 的配置（模型 Key、只读账号口令），identity / platform 也就被迫带上这些配置。
 *
 * <p>没有实现时（identity、platform、db-migrator 进程里都没有）：跳过授权并记日志。
 * 只读账号的授权是幂等的、覆盖面会在下次 app/ai 服务启动时补齐，所以"这次没铺到"不是数据问题。
 */
@FunctionalInterface
public interface ReadOnlyAccountGranter {

    void grantOnDatabases(List<String> databases);
}
