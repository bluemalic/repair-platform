package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户（学校）。
 *
 * <p>不含 {@code deleted}：租户不做删除，停用即可——与"账号不做删除"同一条理由
 * （历史工单要引用它，删掉之后同名学校也建不回来，见 docs/03 §5.4 的账号约定）。
 */
@Data
@Schema(description = "租户（学校）")
public class TenantVO {

    private Long id;

    @Schema(description = "学校名称")
    private String name;

    @Schema(description = "学校编码（登录时填的学校编码）")
    private String code;

    @Schema(description = "联系人")
    private String contact;

    @Schema(description = "联系电话")
    private String phone;

    @Schema(description = "状态 1启用 0停用")
    private Integer status;

    @Schema(description = "开通时间")
    private LocalDateTime createTime;
}
