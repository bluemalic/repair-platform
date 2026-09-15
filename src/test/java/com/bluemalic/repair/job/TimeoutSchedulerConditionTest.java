package com.bluemalic.repair.job;

import com.bluemalic.repair.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置契约测试（纯单测，不起 Spring 上下文）。
 *
 * <p>它防的是一种**没有别的测试能发现**的静默失败：测试侧用
 * {@code repair.timeout.scheduler-enabled=false} 关掉调度器，生产侧靠
 * {@code matchIfMissing=true} 默认开启。如果哪天有人改了其中任一侧的属性名或前缀，
 * 测试依旧全绿，但调度器在**生产和测试里都不会启动**——超时自动关闭彻底失效却没人报错。
 */
class TimeoutSchedulerConditionTest {

    private static final String PROPERTY = "repair.timeout.scheduler-enabled";

    @Test
    void productionDefaultIsOnAndTestsTurnItOff() {
        ConditionalOnProperty condition = TimeoutScheduler.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).isNotNull();
        assertThat(condition.prefix() + "." + condition.name()[0]).isEqualTo(PROPERTY);
        assertThat(condition.matchIfMissing()).as("生产未配置该属性时必须默认开启").isTrue();

        SpringBootTest boot = IntegrationTest.class.getAnnotation(SpringBootTest.class);
        assertThat(boot.properties()).as("测试必须按同一个属性名关掉调度器")
                .contains(PROPERTY + "=false");
    }
}