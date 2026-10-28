package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.AuditAction;
import com.bluemalic.repair.common.AuditTarget;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.dto.TenantSsoConfigDTO;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.entity.TenantSsoConfig;
import com.bluemalic.repair.mapper.TenantMapper;
import com.bluemalic.repair.mapper.TenantSsoConfigMapper;
import com.bluemalic.repair.service.AuditService;
import com.bluemalic.repair.service.TenantSsoConfigService;
import com.bluemalic.repair.sso.SsoProtocol;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import com.bluemalic.repair.vo.TenantSsoConfigVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 学校统一认证配置的维护（阶段 7 · ADR-022）。
 *
 * <p>两条硬规则：**读平台库**（`tenant` 与 `tenant_sso_config` 都只在那里）、
 * **密钥不回显也不进审计**。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantSsoConfigServiceImpl implements TenantSsoConfigService {

    private static final long PLATFORM_TENANT_ID = 0L;
    private static final int ENABLED = 1;
    private static final int DISABLED = 0;

    private final TenantMapper tenantMapper;
    private final TenantSsoConfigMapper ssoConfigMapper;
    private final AuditService auditService;

    @Override
    public TenantSsoConfigVO get(long tenantId) {
        Tenant tenant = requireTenant(tenantId);
        TenantSsoConfig config = TenantRoutingContext.callControl(() -> ssoConfigMapper.selectById(tenantId));
        return toVO(tenant.getId(), config);
    }

    @Override
    public TenantSsoConfigVO update(long tenantId, TenantSsoConfigDTO dto) {
        Tenant tenant = requireTenant(tenantId);
        TenantSsoConfig existing = TenantRoutingContext.callControl(() -> ssoConfigMapper.selectById(tenantId));

        TenantSsoConfig target = existing == null ? new TenantSsoConfig() : existing;
        target.setTenantId(tenantId);
        target.setEnabled(Integer.valueOf(ENABLED).equals(dto.getEnabled()) ? ENABLED : DISABLED);
        target.setProtocol(StringUtils.hasText(dto.getProtocol()) ? dto.getProtocol().trim().toUpperCase() : "OAUTH2");
        target.setAuthorizeUrl(dto.getAuthorizeUrl());
        target.setTokenUrl(dto.getTokenUrl());
        target.setUserinfoUrl(dto.getUserinfoUrl());
        target.setClientId(dto.getClientId());
        // 留空 = 不修改：平台运营改别的字段时不必重新输入密钥
        if (StringUtils.hasText(dto.getClientSecret())) {
            target.setClientSecret(dto.getClientSecret());
        }
        target.setScope(dto.getScope());
        target.setCasLoginUrl(dto.getCasLoginUrl());
        target.setCasValidateUrl(dto.getCasValidateUrl());
        target.setUsernameAttribute(StringUtils.hasText(dto.getUsernameAttribute())
                ? dto.getUsernameAttribute().trim() : "username");
        target.setAdminRedirectUrl(dto.getAdminRedirectUrl());
        target.setH5RedirectUrl(dto.getH5RedirectUrl());
        validate(target);

        if (existing == null) {
            TenantRoutingContext.callControl(() -> ssoConfigMapper.insert(target));
        } else {
            TenantRoutingContext.callControl(() -> ssoConfigMapper.updateById(target));
        }
        log.info("更新学校统一认证配置 tenantId={} enabled={} protocol={}",
                tenantId, target.enabled(), target.getProtocol());
        auditService.record(AuditAction.TENANT_SSO_UPDATE, AuditTarget.TENANT, tenantId, tenant.getName(),
                "修改学校 " + tenant.getName() + " 的统一认证配置：" + summary(target)
                        + "（密钥" + (StringUtils.hasText(target.getClientSecret()) ? "已配置" : "未配置") + "）");
        return toVO(tenantId, target);
    }

    // ==================== 内部 ====================

    /** 协议相关的必填项：错误信息要说清"缺哪个"，平台运营照着填就行。 */
    private void validate(TenantSsoConfig config) {
        SsoProtocol protocol = SsoProtocol.parse(config.getProtocol());
        List<String> missing = new ArrayList<>();
        if (protocol == SsoProtocol.OAUTH2) {
            require(missing, config.getAuthorizeUrl(), "OAuth2 授权地址");
            require(missing, config.getTokenUrl(), "OAuth2 换 token 地址");
            require(missing, config.getUserinfoUrl(), "OAuth2 用户信息地址");
            require(missing, config.getClientId(), "OAuth2 客户端 ID");
        } else {
            require(missing, config.getCasLoginUrl(), "CAS 登录地址");
            require(missing, config.getCasValidateUrl(), "CAS 校验地址");
        }
        if (!missing.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "还缺：" + String.join("、", missing));
        }
        if (config.enabled()
                && !StringUtils.hasText(config.getAdminRedirectUrl())
                && !StringUtils.hasText(config.getH5RedirectUrl())) {
            // 启用时至少要有一个落点，否则登录成功后无处可去（回调会直接报错）
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "启用统一认证时至少要配一个登录成功后的跳转地址（管理端或 H5）");
        }
    }

    private void require(List<String> missing, String value, String label) {
        if (!StringUtils.hasText(value)) {
            missing.add(label);
        }
    }

    /** 审计摘要：只说改了哪些字段，**不带密钥**（AGENTS §5.9）。 */
    private String summary(TenantSsoConfig config) {
        return (config.enabled() ? "启用" : "停用") + "，协议 " + config.getProtocol();
    }

    private Tenant requireTenant(long tenantId) {
        Tenant tenant = TenantRoutingContext.callControl(() -> tenantMapper.selectOne(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getId, tenantId)
                .ne(Tenant::getId, PLATFORM_TENANT_ID)));
        if (tenant == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "租户不存在");
        }
        return tenant;
    }

    private TenantSsoConfigVO toVO(long tenantId, TenantSsoConfig config) {
        TenantSsoConfigVO vo = new TenantSsoConfigVO();
        vo.setTenantId(tenantId);
        vo.setConfigured(config != null);
        vo.setEnabled(config != null && config.enabled());
        vo.setProtocol(config == null ? "OAUTH2" : config.getProtocol());
        vo.setAuthorizeUrl(config == null ? null : config.getAuthorizeUrl());
        vo.setTokenUrl(config == null ? null : config.getTokenUrl());
        vo.setUserinfoUrl(config == null ? null : config.getUserinfoUrl());
        vo.setClientId(config == null ? null : config.getClientId());
        vo.setClientSecretConfigured(config != null && StringUtils.hasText(config.getClientSecret()));
        vo.setScope(config == null ? null : config.getScope());
        vo.setCasLoginUrl(config == null ? null : config.getCasLoginUrl());
        vo.setCasValidateUrl(config == null ? null : config.getCasValidateUrl());
        vo.setUsernameAttribute(config == null ? "username" : config.getUsernameAttribute());
        vo.setAdminRedirectUrl(config == null ? null : config.getAdminRedirectUrl());
        vo.setH5RedirectUrl(config == null ? null : config.getH5RedirectUrl());
        return vo;
    }
}
