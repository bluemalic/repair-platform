package com.bluemalic.repair.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.bluemalic.repair.interceptor.TicketDataScopeHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：Mapper 扫描 + 插件注册。
 *
 * <p>插件顺序有讲究：数据权限（改写 SQL 注入范围条件）必须先于分页执行，
 * 否则 count 语句会漏掉权限条件，total 与列表对不上。
 */
@Configuration
@MapperScan("com.bluemalic.repair.mapper")
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(TicketDataScopeHandler dataScopeHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // ADR-002：数据权限收敛到框架层——学生只见自己的单、维修工只见负责楼栋，任何 Mapper 不手写条件
        interceptor.addInnerInterceptor(new DataPermissionInterceptor(dataScopeHandler));
        // 分页插件必须是最后添加的内部拦截器
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
