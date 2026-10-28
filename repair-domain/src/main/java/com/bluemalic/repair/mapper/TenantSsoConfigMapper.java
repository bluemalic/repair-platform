package com.bluemalic.repair.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bluemalic.repair.entity.TenantSsoConfig;

/**
 * 学校统一身份认证配置（阶段 7 · ADR-022）。
 *
 * <p>这张表只在**平台库**，所以调用方都要经 {@code TenantRoutingContext.callControl(...)} 读它
 * （与 `TenantMapper` 同一条规矩，见 AGENTS §6）。
 */
public interface TenantSsoConfigMapper extends BaseMapper<TenantSsoConfig> {
}
