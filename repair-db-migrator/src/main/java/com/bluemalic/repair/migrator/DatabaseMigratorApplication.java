package com.bluemalic.repair.migrator;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 数据库迁移作业入口（阶段 3.2）。
 *
 * <p><b>为什么是 WebApplicationType.NONE</b>：它只在发布时跑一次、跑完就退出。
 * 起一个 web 容器既没有端点也没有意义，还会让编排系统误以为它是个常驻服务。
 *
 * <p><b>退出码</b>：迁移失败时异常会一路上抛，{@link SpringApplication#exit} 把它转成
 * 非 0 退出码——发布脚本据此中止，不会带着半迁移的库继续重启服务。
 *
 * <p>用法：{@code java -jar repair-db-migrator-exec.jar}，配置与其它服务同一套环境变量
 * （{@code MYSQL_HOST/MYSQL_PORT/MYSQL_DATABASE/MYSQL_USERNAME/MYSQL_PASSWORD}）。
 */
@SpringBootApplication(scanBasePackages = "com.bluemalic.repair.migrator")
public class DatabaseMigratorApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(DatabaseMigratorApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        System.exit(SpringApplication.exit(application.run(args)));
    }

    @Bean
    ApplicationRunner migrateOnStartup(TenantSchemaMigrator migrator) {
        return args -> migrator.migrateAll();
    }
}
