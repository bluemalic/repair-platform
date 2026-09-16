package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 维修工信息（管理端列表 / 新增返回）。
 *
 * <p>**没有 password 字段**——实体里有，但这个 VO 是给前端看的，密码散列也不该出去。
 * 这也是"禁止把 entity 直接当接口出入参"（AGENTS §5.4）的价值所在。
 */
@Data
@Schema(description = "维修工信息")
public class WorkerVO {

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "工号（登录名）")
    private String username;

    @Schema(description = "姓名")
    private String realName;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "状态 1启用 0停用")
    private Integer status;

    /** 列表接口一次批量查出，避免前端为每行再发一次请求；空时给空数组，前端不必判 null。 */
    @Schema(description = "负责的楼栋ID列表")
    private List<Long> buildingIds = Collections.emptyList();

    @Schema(description = "负责的楼栋名称列表")
    private List<String> buildingNames = Collections.emptyList();
}
