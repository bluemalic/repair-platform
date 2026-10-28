package com.bluemalic.repair.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis 缓存（阶段 5 · ADR-020）。
 *
 * <p><b>只缓存"很少变、但读得很频繁"的两类数据</b>：
 * <ul>
 *   <li>鉴权结果：每个带 {@code @SaCheckPermission} 的请求都会回调 {@code StpInterface}，
 *       原来每次都要查三张表。缓存后热路径上不再回库</li>
 *   <li>启用中的报修类别：学生报修表单与 H5 每次打开都要拉一遍，而它只有管理员偶尔改</li>
 * </ul>
 * 工单、统计这类"每次都可能不同"的数据**不缓存**——缓存它们只会制造不一致。
 *
 * <p><b>共享同一个 Redis 库、靠前缀区分用途</b>（ADR-016 的约定）：所有键都带 {@code cache:} 前缀，
 * 会话（Sa-Token）与限流计数在同一个库里但前缀不同。这样做的前提是**每个缓存都必须有 TTL**
 * （与 ADR-016 的"除超时队列外所有键必须带 TTL"一致）。
 *
 * <p><b>Redis 挂了不能让业务挂</b>：{@link #errorHandler()} 把缓存的读 / 写 / 清失败降级成
 * "这次没命中"，方法照常回库执行——与限流"Redis 故障时放行"是同一条原则：**缓存是加速器，
 * 不是正确性的依赖**。
 */
@Slf4j
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    /** 用户 → 权限码（每个用户一份，值是纯字符串列表，序列化不依赖具体类型） */
    public static final String CACHE_USER_PERMS = "authz-user-perms";

    /** 用户 → 角色码 */
    public static final String CACHE_USER_ROLES = "authz-user-roles";

    /** 租户 → 启用中的报修类别 */
    public static final String CACHE_ENABLED_CATEGORIES = "enabled-categories";

    private static final Duration AUTHZ_TTL = Duration.ofMinutes(30);

    private static final Duration DICTIONARY_TTL = Duration.ofMinutes(5);

    // 注意：这里**不是**覆盖 CachingConfigurer#cacheManager()——那个方法不带参数。
    // 带参数的是给 Spring 注入连接工厂的 Bean 方法；写成 @Override 会编译不过（踩过一次）。
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                // 前缀是"共享库靠前缀区分用途"的落点：cache:* 一眼能看出是可丢的缓存
                .prefixCacheNameWith("cache:")
                .entryTtl(DICTIONARY_TTL)
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                // 值用 JDK 序列化：**读写天然对称**，不需要处理泛型类型元数据。
                // 踩过的坑：Jackson 的默认类型信息对"根数组"不写类型 id，读回来却要求
                // （Could not resolve type id 'ticket:create'）——缓存值都是 List<String> /
                // List<CategoryVO> 这类小对象，JDK 序列化在这里更省心，代价是 Redis 里看不出来（可接受：缓存是可丢的）。
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new JdkSerializationRedisSerializer()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                // 鉴权结果变的机会更小、也更怕频繁回库，TTL 给长一点
                .withCacheConfiguration(CACHE_USER_PERMS, base.entryTtl(AUTHZ_TTL))
                .withCacheConfiguration(CACHE_USER_ROLES, base.entryTtl(AUTHZ_TTL))
                .withCacheConfiguration(CACHE_ENABLED_CATEGORIES, base.entryTtl(DICTIONARY_TTL))
                .build();
    }


    /**
     * 缓存故障降级：读失败当未命中、写 / 清失败只记日志。
     *
     * <p>不降级的话，Redis 一抖动就会变成"登录也报错、看类别也报错"——
     * 而这些本来都是有数据库兜底的读操作。
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("缓存读取失败，按未命中处理 cache={} key={} err={}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("缓存写入失败，忽略 cache={} key={} err={}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.warn("缓存清理失败，忽略 cache={} key={} err={}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("缓存清空失败，忽略 cache={} err={}", cache.getName(), exception.getMessage());
            }
        };
    }
}
