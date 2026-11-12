package io.github.lowvisjacket.idempotency.autoconfigure;

import io.github.lowvisjacket.idempotency.store.IdempotencyStore;
import io.github.lowvisjacket.idempotency.store.RedisIdempotencyStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Chooses and creates the {@link IdempotencyStore}.
 *
 * <p>Today the only choice is Redis: if Spring Data Redis is on the classpath and the application has a
 * {@link StringRedisTemplate}, a {@link RedisIdempotencyStore} is created. An application that defines its own
 * {@link IdempotencyStore} bean keeps it, and nothing here is created. With no Redis there is no store and the
 * library stays off (see {@link IdempotencyAutoConfiguration}). A JDBC store will be added here later.
 *
 * <p><b>Redis is an optional dependency</b> of this library. An application without Redis must still start, so the
 * Redis classes must not be touched unless they exist. The trick is to keep everything that mentions Redis types
 * inside the nested {@link RedisStoreConfiguration}, guarded by {@code @ConditionalOnClass}: Spring reads the
 * condition from the annotation text, without loading the Redis classes, and skips the nested class if the
 * condition fails. (Putting the Redis bean directly on the outer class would crash with
 * {@code NoClassDefFoundError} for applications without Redis.)
 *
 * <p><b>Order:</b> the {@link StringRedisTemplate} is created by Boot's own Redis auto-configuration, which must run
 * first, otherwise the "is there a template?" check sees nothing.
 *
 * @see IdempotencyAutoConfiguration
 */
@AutoConfiguration(after = DataRedisAutoConfiguration.class)
public class IdempotencyStoreAutoConfiguration {

    /** The Redis choice. Only read by Spring when Redis is on the classpath. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    static class RedisStoreConfiguration {

        /**
         * @param redisTemplate Boot's string-based Redis template
         * @param objectMapper  the application's Jackson mapper, used to write records as JSON
         * @return the Redis-backed store
         */
        @Bean
        @ConditionalOnMissingBean(IdempotencyStore.class)
        RedisIdempotencyStore redisIdempotencyStore(StringRedisTemplate redisTemplate) {
            return new RedisIdempotencyStore(redisTemplate);
        }
    }
}
