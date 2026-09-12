package com.bluemalic.repair.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：Mapper 扫描 + 插件注册。
 *
 * <p>分页插件必须注册。不注册的话，传 {@code IPage} 进去 SQL 里不会拼 {@code LIMIT}，
 * 会退化成"全表查出来再内存分页"——它不报错，只是数据量上来后越来越慢，
 * 属于典型的"上线后才暴露"的问题。
 *
 * <p>后续的数据权限拦截器（见 ADR-002）也加在这个 {@code MybatisPlusInterceptor} 里，
 * 注意顺序：多个内部拦截器同时使用时，分页插件要放在最后添加。
 */
@Configuration
@MapperScan("com.bluemalic.repair.mapper")
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
