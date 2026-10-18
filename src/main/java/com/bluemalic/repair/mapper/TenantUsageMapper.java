package com.bluemalic.repair.mapper;

import com.bluemalic.repair.vo.TenantUsageVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 平台租户列表的配额用量统计（阶段 5 · ADR-020）。
 *
 * <p>一次按租户分组取回学生 / 维修工数量：列表页一屏十几所学校，逐个 `COUNT(*)` 就是十几个来回，
 * 而这一条 SQL 就够了。`deleted = 0` 显式写（原生 SQL 不走逻辑删除的自动过滤），
 * 与配额校验的口径保持一致——**含停用**。
 */
public interface TenantUsageMapper {

    List<TenantUsageVO> countByTenant(@Param("tenantIds") List<Long> tenantIds);
}
