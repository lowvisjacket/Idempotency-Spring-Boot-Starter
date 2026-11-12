package io.github.lowvisjacket.idempotency.autoconfigure;

import io.github.lowvisjacket.idempotency.store.IdempotencyStore;
import io.github.lowvisjacket.idempotency.web.IdempotentHandlerValidator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The counterpart of {@link IdempotencyAutoConfiguration} for the case where there is no store: instead of backing
 * off silently and leaving every {@code @Idempotent} endpoint unprotected, the application fails to start.
 */
@AutoConfiguration(after = {IdempotencyStoreAutoConfiguration.class, IdempotencyCircuitBreakerAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = IdempotencyProperties.PREFIX, name = "enabled", matchIfMissing = true)
@ConditionalOnMissingBean(IdempotencyStore.class)
public class IdempotencyMissingStoreAutoConfiguration {

    @Bean
    public SmartInitializingSingleton idempotencyMissingStoreCheck(ObjectProvider<RequestMappingHandlerMapping> mappings) {
        return () -> mappings.orderedStream()
                .forEach(mapping -> IdempotentHandlerValidator.requireNoIdempotentHandlers(mapping.getHandlerMethods().values()));
    }
}
