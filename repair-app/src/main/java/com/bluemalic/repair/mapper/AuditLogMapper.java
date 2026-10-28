package com.bluemalic.repair.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bluemalic.repair.entity.AuditLog;

/**
 * 审计日志 Mapper。
 *
 * <p>只用到 {@code insert} / {@code selectPage}：这张表**只增不改**
 * （实体里没有 updateTime / deleted，是刻意的，见 {@code AuditLog} 的类注释）。
 *
 * <p>注意：{@code audit_log} <b>不在数据权限拦截器的名单里</b>（那个名单只有 ticket 与 notification），
 * 所以查询必须**显式写 tenant_id 条件**（AGENTS §5.6）。
 */
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
