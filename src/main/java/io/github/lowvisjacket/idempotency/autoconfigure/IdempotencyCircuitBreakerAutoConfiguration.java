package io.github.lowvisjacket.idempotency.autoconfigure;

import io.github.lowvisjacket.idempotency.autoconfigure.IdempotencyProperties.CircuitBreakerProperties;
import io.github.lowvisjacket.idempotency.store.CircuitBreakerIdempotencyStore;
import io.github.lowvisjacket.idempotency.store.IdempotencyStore;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Puts a circuit breaker around the {@link IdempotencyStore} when Resilience4j is available.
 *
 * <p>Resilience4j is an <b>optional</b> dependency, so everything that mentions its types lives in
 * {@link BreakerConfiguration}, which Spring only reads when {@code CircuitBreaker} is on the classpath (the same
 * trick as the Redis store configuration). Without Resilience4j the application starts and the store is used as it is.
 *
 * <p>This class runs after {@link IdempotencyStoreAutoConfiguration} (so the store bean definition exists) and
 * before {@link IdempotencyAutoConfiguration}. It is switched off by {@code idempotency.circuit-breaker.enabled=false}.
 * It enables {@link IdempotencyProperties} itself, because it cannot count on the main auto-configuration (which only
 * runs when a store exists and the application is a web application).
 *
 * <p>What it provides:
 * <ol>
 *   <li>One {@code CircuitBreaker} bean named {@code idempotencyCircuitBreaker}, built from
 *       {@code idempotency.circuit-breaker.*}. An application bean with that name wins.</li>
 *   <li>Every {@link IdempotencyStore} bean, including one the application defined itself, is replaced by a
 *       {@link CircuitBreakerIdempotencyStore} around it.</li>
 *   <li>A store that already is a {@link CircuitBreakerIdempotencyStore} is left alone, and other beans are returned
 *       unchanged.</li>
 * </ol>
 *
 * @see CircuitBreakerIdempotencyStore
 * @see IdempotencyProperties.CircuitBreakerProperties
 */
@AutoConfiguration(after = IdempotencyStoreAutoConfiguration.class, before = IdempotencyAutoConfiguration.class)
@ConditionalOnProperty(prefix = IdempotencyProperties.PREFIX + ".circuit-breaker", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyCircuitBreakerAutoConfiguration {

    /** Name of the breaker bean. An application bean with this name replaces ours. */
    static final String BREAKER_BEAN_NAME = "idempotencyCircuitBreaker";

    /** Everything that mentions Resilience4j types. Only read by Spring when Resilience4j is on the classpath. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(CircuitBreaker.class)
    static class BreakerConfiguration {

        /**
         * Builds the breaker from the settings, using a count-based window (the last N calls).
         *
         * @param properties the bound {@code idempotency.*} settings
         * @return the breaker, named {@code idempotency}
         */
        @Bean(BREAKER_BEAN_NAME)
        @ConditionalOnMissingBean(name = BREAKER_BEAN_NAME)
        CircuitBreaker idempotencyCircuitBreaker(IdempotencyProperties properties) {
            CircuitBreakerProperties settings = properties.getCircuitBreaker();
            CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                    .slidingWindowType(SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(settings.getSlidingWindowSize())
                    .minimumNumberOfCalls(settings.getMinimumNumberOfCalls())
                    .failureRateThreshold(settings.getFailureRateThreshold())
                    .waitDurationInOpenState(settings.getWaitDurationInOpenState())
                    .permittedNumberOfCallsInHalfOpenState(settings.getPermittedCallsInHalfOpenState())
                    .build();
            return CircuitBreaker.of("idempotency", config);
        }

        /**
         * Wraps store beans. {@code static} because a post-processor is created before the configuration class
         * itself, and the breaker comes through an {@link ObjectProvider} so it is only built when a store bean
         * actually appears (asking for it directly would build it too early).
         *
         * @param breaker the breaker, fetched lazily
         * @return the post-processor
         */
        @Bean
        static BeanPostProcessor idempotencyStoreBreakerPostProcessor(ObjectProvider<CircuitBreaker> breaker) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof IdempotencyStore store && !(bean instanceof CircuitBreakerIdempotencyStore)) {
                        return new CircuitBreakerIdempotencyStore(store, breaker.getObject());
                    }
                    return bean;
                }
            };
        }
    }
}
