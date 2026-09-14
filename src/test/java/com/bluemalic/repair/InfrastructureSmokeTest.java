package com.bluemalic.repair;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 基础设施连通性冒烟测试：确认数据源与 Redis 真的能用。
 *
 * <p>为什么必须有它：Hikari 是懒连接的，{@code contextLoads} 即使连不上数据库也会通过。
 * 只靠上下文测试，CI 就是"假绿"——配了 service container 也证明不了接线是对的。
 */
@IntegrationTest
class InfrastructureSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void mysqlIsReachableAndSchemaIsLoaded() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM repair_code")) {
            assertThat(resultSet.next()).isTrue();
            // 建表脚本初始化了 5 条报修码，这里只断言表可读，不与具体数据量耦合
            assertThat(resultSet.getInt(1)).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void redisIsReachable() {
        String key = "smoke:" + UUID.randomUUID();
        stringRedisTemplate.opsForValue().set(key, "ok");
        try {
            assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo("ok");
        } finally {
            stringRedisTemplate.delete(key);
        }
    }
}
