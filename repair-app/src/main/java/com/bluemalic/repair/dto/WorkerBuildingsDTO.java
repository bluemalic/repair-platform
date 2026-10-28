package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 设置维修工负责楼栋入参。**语义是全量替换**：传什么就是他的全部负责楼栋，
 * 没传到的等于解除负责关系——比"增删两个接口"少了"删到一半"的中间状态。
 *
 * <p>传空数组是合法操作，表示这个师傅暂时不负责任何楼栋（数据权限下他看不到任何工单）。
 */
@Data
@Schema(description = "设置维修工负责楼栋入参（全量替换）")
public class WorkerBuildingsDTO {

    @Schema(description = "负责的楼栋ID列表，传空数组表示不负责任何楼栋")
    @NotNull(message = "楼栋列表不能为空，清空请传空数组")
    @Size(max = 100, message = "一个师傅最多负责 100 栋楼")
    private List<Long> buildingIds;
}
