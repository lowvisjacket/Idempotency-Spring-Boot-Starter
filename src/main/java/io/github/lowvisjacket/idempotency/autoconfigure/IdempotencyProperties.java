package io.github.lowvisjacket.idempotency.autoconfigure;

import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings an application can change in {@code application.properties} / {@code application.yml}, all under the
 * prefix {@value #PREFIX}:
 *
 * <pre>
 * idempotency.enabled=true
 * idempotency.max-response-body-bytes=65536
 * </pre>
 *
 * <p>This class only holds values; it does no work. It is bound by Spring Boot (setters are how the values get in),
 * so every property needs a getter and a setter.
 *
 * <p>Not yet configurable (they are constants in {@link IdempotencyDefaults} for now): the request body cap, the
 * header name (it lives on {@code @Idempotent}), and the default TTL.
 *
 * @see IdempotencyAutoConfiguration
 */
@ConfigurationProperties(prefix = IdempotencyProperties.PREFIX)
public class IdempotencyProperties {

    /** The property prefix: everything is {@code idempotency.*}. */
    public static final String PREFIX = "idempotency";

    /** Boolean indicating if this library is enabled **/
    private boolean enabled = true;

    /** Integer representing the max byte size of the response body **/
    private int maxResponseBodyBytes = IdempotencyDefaults.MAX_RESPONSE_SIZE_BYTES;

    /** @return true if the library is switched on (the default) */
    public boolean isEnabled() {
        return enabled;
    }

    /** @param enabled false switches the whole library off: no beans, no filters, no interceptor */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return the most response-body bytes held in memory so the response can be saved */
    public int getMaxResponseBodyBytes() {
        return maxResponseBodyBytes;
    }

    /**
     * @param maxResponseBodyBytes zero or more; a bigger response is still sent in full, it just is not saved
     * @throws IllegalArgumentException if negative
     */
    public void setMaxResponseBodyBytes(int maxResponseBodyBytes) {
        if (maxResponseBodyBytes < 0) throw new IllegalArgumentException("maxResponseBodyBytes must be equal or greater than 0: " + maxResponseBodyBytes);
        this.maxResponseBodyBytes = maxResponseBodyBytes;
    }
}
