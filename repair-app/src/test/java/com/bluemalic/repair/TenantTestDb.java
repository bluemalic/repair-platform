package com.bluemalic.repair;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 让测试跑在**真实的租户库**上（默认 {@link TestTenantBootstrap#SCHEMA}），而不是平台库。
 *
 * <p>不标这个注解的测试保持原来的行为：路由键为空、所有查询落在平台库 `repair`。
 * 这样既让需要验证"路由真的换库"的用例跑在真库上，又不会把整套集成测试从平台库搬走
 * （那会连带影响演示种子数据、平台账号、审计这些只在平台库里有数据的用例）。
 *
 * <p>可以标在类上（整个类都切）或方法上（只切这一个用例）。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantTestDb {

    /** 租户库名；必须由 {@link TestTenantBootstrap} 建好。 */
    String value() default TestTenantBootstrap.SCHEMA;
}
