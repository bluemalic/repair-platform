package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "完工上报入参")
public class TicketFinishDTO {

    @Schema(description = "维修结果说明")
    @NotBlank(message = "维修结果说明不能为空")
    @Size(max = 500, message = "维修结果说明最长 500 字")
    private String resultDesc;

    @Schema(description = "维修后照片 URL 数组")
    @Size(max = 9, message = "维修后照片最多 9 张")
    private List<String> resultImages;
}
