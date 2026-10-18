package com.bluemalic.repair.service;

import com.bluemalic.repair.vo.TenantDatabaseVO;

/**
 * 存量租户的**数据搬迁**（阶段 6 第 2 步 · ADR-021 决定 7b/7c）。
 *
 * <p>三件事，对应三个动作，**顺序不能反**：
 * <ol>
 *   <li>{@link #copy}：建库 → 跑租户库迁移 → 逐表搬 → 逐表校验。**可以在学校正常使用时做**：
 *       它是把数据复制一份到新库，源库一条记录都不动，读到的是快照（不锁源表）；</li>
 *   <li>{@link #switchToDedicated}：再校验一次，一致才把 `tenant.db_schema` 指过去。
 *       这是**唯一改变行为**的一步，只有几秒；期间该学校的请求被直接挡住
 *      （{@code TenantDataMigrator} 的翻转标记）；</li>
 *   <li>{@link #status}：随时可查"数据在哪、两边是否一致"。</li>
 * </ol>
 *
 * <p><b>为什么不做双写</b>（ADR-021 决定 7c）：双写要处理"写一半失败""两边不一致谁说了算"，
 * 复杂度和一致性风险远大于"选个没人用的时刻，几秒钟切完"。代价是**切换窗口内的写入会留在源库**，
 * 所以：搬迁与切换之间不要隔太久，切换后核对一眼（{@link #status} 在校验时能看出源库是否又多出东西）。
 * 这条边界写进 docs/05 §5.4 的操作步骤里，不靠"记得"。
 *
 * <p><b>回退</b>：切换之后新库开始接收写入，此时把 `db_schema` 改回空**会丢掉切换之后的数据**。
 * 所以回退窗口 = "切换后到学校真正开始使用之间"；共享库里的那份旧数据一直保留着（ADR-021 的"确认后再归档"）。
 */
public interface TenantDatabaseService {

    /** 数据在哪、目标库有没有建、两边是否逐表一致（一致性的比对是 O(行数)，会在两个库上各扫一遍）。 */
    TenantDatabaseVO status(long tenantId);

    /** 搬家（幂等，可反复跑）：建库 + 跑迁移 + 逐表搬运 + 逐表校验，返回逐表结果。 */
    TenantDatabaseVO copy(long tenantId);

    /** 切换到这所学校自己的库：再校验一次 → 一致才切。不一致直接拒绝并说清是哪张表。 */
    TenantDatabaseVO switchToDedicated(long tenantId);
}
