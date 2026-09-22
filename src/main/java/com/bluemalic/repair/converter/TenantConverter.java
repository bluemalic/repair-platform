package com.bluemalic.repair.converter;

import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.vo.TenantAdminVO;
import com.bluemalic.repair.vo.TenantVO;

/** 租户与租户管理员的实体 → VO。 */
public final class TenantConverter {

    private TenantConverter() {
    }

    public static TenantVO toVO(Tenant tenant) {
        TenantVO vo = new TenantVO();
        vo.setId(tenant.getId());
        vo.setName(tenant.getName());
        vo.setCode(tenant.getCode());
        vo.setContact(tenant.getContact());
        vo.setPhone(tenant.getPhone());
        vo.setStatus(tenant.getStatus());
        vo.setCreateTime(tenant.getCreateTime());
        return vo;
    }

    public static TenantAdminVO toAdminVO(SysUser user) {
        TenantAdminVO vo = new TenantAdminVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setPhone(user.getPhone());
        vo.setStatus(user.getStatus());
        // 与 StudentConverter 同一处理：库里的 1/0 在接口上就是 true/false
        vo.setMustChangePassword(Integer.valueOf(1).equals(user.getMustChangePassword()));
        return vo;
    }
}
