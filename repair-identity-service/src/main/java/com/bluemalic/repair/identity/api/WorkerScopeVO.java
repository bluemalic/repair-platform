package com.bluemalic.repair.identity.api;

import java.util.List;

/**
 * 维修工的范围：负责楼栋 + 擅长类别（阶段 3.2）。
 *
 * <p>派单校验（"这位师傅管不管这栋楼"）与评分（技能维度）需要它。
 * 两张表按 `docs/02` 归 ticket，但**写入**目前还在 identity 的 WorkerService 里
 * （阶段 3.3 随 ticket 服务一起归位）；这里先把读契约定下来，调用方不必直连表。
 */
public record WorkerScopeVO(
        Long workerId,
        List<Long> buildingIds,
        List<Long> skillCategoryIds) {
}
