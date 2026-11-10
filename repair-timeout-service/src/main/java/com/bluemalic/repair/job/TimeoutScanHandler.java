package com.bluemalic.repair.job;

import com.bluemalic.repair.service.impl.TimeoutScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * XXL-JOB 的分片扫描入口（阶段 4 · ADR-029）。
 *
 * <p><b>为什么是一个普通 Bean 而不是 {@code @XxlJob} 方法</b>：项目没有引入 xxl-job 依赖
 * （AGENTS §3：新依赖先说明理由）。XXL-JOB 的 Executor 接入方式是在**运维侧**的 Admin 上配一个
 * 任务，执行器里用 {@code @XxlJob} 调本类；把调度与业务解耦之后，没装 Admin 的环境
 * （线上 lite）用 {@code repair.timeout.scan.trigger=scheduled} 自己跑，不需要为了编译通过
 * 而背上一个只在 2C2G ops 节点上用的依赖。
 *
 * <p>接 Admin 时的三件事（写在这里，避免下次重新摸索）：
 * <ol>
 *   <li>执行器注册到 Admin，任务的路由策略选「分片广播」，分片数 = 执行器实例数；</li>
 *   <li>JobHandler 里读 {@code XxlJobHelper.getShardIndex()} / {@code getShardTotal()}，
 *       转调 {@link #execute(int, int)}；</li>
 *   <li>{@code repair.timeout.scan.trigger} 设成 {@code xxl-job}，把进程内的 @Scheduled 关掉
 *       （否则两边同时扫，虽然幂等但白烧 CPU）。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeoutScanHandler {

    private final TimeoutScanService scanService;

    /**
     * 执行一次分片扫描。
     *
     * @param shardIndex 分片序号（0 基）
     * @param shardTotal 分片总数
     * @return 本轮处理条数（XXL-JOB 的任务日志里能看到）
     */
    public int execute(int shardIndex, int shardTotal) {
        int handled = scanService.scan(shardIndex, shardTotal);
        log.info("XXL-JOB 超时扫描 shard={}/{} 处理 {} 条", shardIndex, shardTotal, handled);
        return handled;
    }
}
