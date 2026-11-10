package com.bluemalic.repair.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 派单评分的权重与负载上限（阶段 3 · ADR-019）。
 *
 * <p><b>为什么权重走配置而不是写成常量</b>：一所学校"看重技能"还是"看重满意度"没有标准答案，
 * 换学校、换季节（比如开学季更看重负载）都要调。写成常量就得改代码重新发版；
 * 而且它是**给人看的参数**——把五项权重放在配置里，答辩时可以直接说清"当前这套权重是什么、
 * 为什么"（默认 技能 0.4 / 满意度 0.3 / 负载 0.2 / 距离 0.1 / 值班 0.1）。
 *
 * <p><b>四项权重之和会自动归一化</b>（总分始终落在 0~1）：允许学校把权重都放大成 4/3/2/1/1，
 * 结果不变。但五项全为 0 是配置错误，启动即报错——那会让所有候选同分，排序退化成"按工号排"，
 * 而使用者只会以为"评分坏了"。
 */
@Getter
@Component
public class DispatchProperties {

    private final double weightSkill;

    private final double weightSatisfaction;

    private final double weightLoad;

    private final double weightDistance;

    private final double weightShift;

    /** 负载的"封顶单数"：进行中工单达到这个数，负载得分记为 0（负分不出现） */
    private final int loadCap;

    public DispatchProperties(@Value("${repair.dispatch.weight-skill:0.4}") double weightSkill,
                              @Value("${repair.dispatch.weight-satisfaction:0.3}") double weightSatisfaction,
                              @Value("${repair.dispatch.weight-load:0.2}") double weightLoad,
                              @Value("${repair.dispatch.weight-distance:0.1}") double weightDistance,
                              @Value("${repair.dispatch.weight-shift:0.1}") double weightShift,
                              @Value("${repair.dispatch.load-cap:5}") int loadCap) {
        if (weightSkill < 0 || weightSatisfaction < 0 || weightLoad < 0 || weightDistance < 0 || weightShift < 0) {
            throw new IllegalStateException("派单评分的权重不能为负数，请检查 repair.dispatch.weight-* 配置");
        }
        if (weightSkill + weightSatisfaction + weightLoad + weightDistance + weightShift <= 0) {
            throw new IllegalStateException("派单评分的五项权重不能全为 0，请检查 repair.dispatch.weight-* 配置");
        }
        if (loadCap <= 0) {
            throw new IllegalStateException("派单评分的负载上限必须大于 0，请检查 repair.dispatch.load-cap");
        }
        this.weightSkill = weightSkill;
        this.weightSatisfaction = weightSatisfaction;
        this.weightLoad = weightLoad;
        this.weightDistance = weightDistance;
        this.weightShift = weightShift;
        this.loadCap = loadCap;
    }

    /** 五项权重之和：总分按它归一化，所以"把权重都放大成 4/3/2/1/1"不会改变排序 */
    public double weightSum() {
        return weightSkill + weightSatisfaction + weightLoad + weightDistance + weightShift;
    }
}
