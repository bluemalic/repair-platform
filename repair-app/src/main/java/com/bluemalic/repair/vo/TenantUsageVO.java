package com.bluemalic.repair.vo;

import lombok.Data;

/** 按租户 + 用户类型统计的账号数（平台租户列表的配额展示用）。 */
@Data
public class TenantUsageVO {

    private Long tenantId;

    /** 1 学生 / 2 维修工 / 3 后勤 / 4 平台 */
    private Integer userType;

    private Long accountCount;
}
