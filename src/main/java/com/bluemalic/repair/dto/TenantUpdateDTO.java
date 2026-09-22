package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改租户资料（名称 / 联系人 / 电话）。
 *
 * <p>不含编码与状态，各有理由：
 * <ul>
 *   <li><b>编码不给改</b>：它是师生天天输入的登录参数，改了等于全校的登录方式变了；
 *       而且 {@code uk_code} 是唯一键，改它还要处理冲突</li>
 *   <li><b>状态单独一个入口</b>：停用会踢掉该租户全部在线用户，折进这个接口的话
 *       每次改个联系人电话都在踢人——与 {@code StudentPasswordDTO} 分开的理由同源</li>
 * </ul>
 */
@Data
@Schema(description = "修改租户资料入参")
public class TenantUpdateDTO {

    @Schema(description = "学校名称")
    @NotBlank(message = "学校名称不能为空")
    @Size(max = 64, message = "学校名称最长 64 位")
    private String name;

    @Schema(description = "联系人")
    @Size(max = 32, message = "联系人最长 32 位")
    private String contact;

    @Schema(description = "联系电话")
    @Size(max = 20, message = "联系电话最长 20 位")
    private String phone;
}
