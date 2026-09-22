package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "批量导入学生入参")
public class StudentImportDTO {

    /**
     * 粘贴的名单，每行一个学号，或"学号,姓名"。
     *
     * <p>为什么是粘贴文本而不是 Excel 上传：学校从教务系统导出名单后，真实的操作路径就是
     * 复制粘贴。为了收 Excel 要在后端引 POI，而解析规则（列顺序、合并单元格、编码）
     * 反而会变成一堆说不清的兼容逻辑——粘贴文本没有这些歧义。
     */
    @Schema(description = "学号名单，每行一个；支持「学号,姓名」两列",
            example = "20260003,张三\n20260004,李四")
    @NotBlank(message = "请粘贴学号名单")
    private String rows;

    @Schema(description = "统一初始口令，8-32 位。这批账号首次登录必须改密")
    @NotBlank(message = "初始口令不能为空")
    @Size(min = 8, max = 32, message = "初始口令需为 8-32 位")
    private String password;
}
