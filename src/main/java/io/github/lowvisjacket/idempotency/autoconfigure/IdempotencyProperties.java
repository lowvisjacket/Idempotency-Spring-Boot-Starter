package io.github.lowvisjacket.idempotency.autoconfigure;

import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings an application can change in {@code application.properties} / {@code application.yml}, all under the
 * prefix {@value #PREFIX}:
 *
 * <pre>
 * idempotency.enabled=true
 * idempotency.max-response-body-bytes=65536
 * idempotency.on-unreplayable-response=release   (or block)
 * idempotency.on-server-error=release            (or replay)
 * idempotency.circuit-breaker.enabled=true
 * idempotency.circuit-breaker.sliding-window-size=20
 * idempotency.circuit-breaker.minimum-number-of-calls=10
 * idempotency.circuit-breaker.failure-rate-threshold=50
 * idempotency.circuit-breaker.wait-duration-in-open-state=30s
 * idempotency.circuit-breaker.permitted-calls-in-half-open-state=3
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

    /** What to do with the key when the finished response is too large or not text and so cannot be replayed. */
    private UnreplayableResponsePolicy onUnreplayableResponse = UnreplayableResponsePolicy.RELEASE;

    /** What to do with the key when the response status is 5xx. */
    private ServerErrorPolicy onServerError = ServerErrorPolicy.RELEASE;

    /**
     * {@code RELEASE}: delete the record, so a retry runs the handler again (the default, and what the README has
     * always said). {@code BLOCK}: keep a record that says the request finished, and answer a retry with
     * {@code 409 response-not-replayable} without running the handler again. Use {@code BLOCK} where running twice
     * is worse than not being able to show the first answer, such as payments.
     */
    public enum UnreplayableResponsePolicy { RELEASE, BLOCK }

    /**
     * {@code RELEASE}: delete the record, so a retry runs the handler again (the default). {@code REPLAY}: save the
     * 5xx response like any other and replay it. An exception that escapes the handler always releases the key,
     * because there is no response to save.
     */
    public enum ServerErrorPolicy { RELEASE, REPLAY }

    /** @return the policy for responses that cannot be replayed */
    public UnreplayableResponsePolicy getOnUnreplayableResponse() {
        return onUnreplayableResponse;
    }

    public void setOnUnreplayableResponse(UnreplayableResponsePolicy onUnreplayableResponse) {
        this.onUnreplayableResponse = java.util.Objects.requireNonNull(onUnreplayableResponse, "onUnreplayableResponse");
    }

    /** @return the policy for 5xx responses */
    public ServerErrorPolicy getOnServerError() {
        return onServerError;
    }

    public void setOnServerError(ServerErrorPolicy onServerError) {
        this.onServerError = java.util.Objects.requireNonNull(onServerError, "onServerError");
    }

    /** Settings of the circuit breaker around the store (used only when Resilience4j is on the classpath). */
    private final CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();

    /** @return the circuit breaker settings (never null) */
    public CircuitBreakerProperties getCircuitBreaker() {
        return circuitBreaker;
    }

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

    /**
     * Settings of the circuit breaker, under {@code idempotency.circuit-breaker.*}. The defaults are used when a
     * property is not set.
     *
     * <p><b>Validation:</b> every setter rejects a bad value with {@link IllegalArgumentException}
     * (Boot then refuses to start and names the property):
     * <ul>
     *   <li>{@code slidingWindowSize}: at least 1;</li>
     *   <li>{@code minimumNumberOfCalls}: at least 1;</li>
     *   <li>{@code failureRateThreshold}: from 1 to 100 (Resilience4j itself rejects 0 and above 100);</li>
     *   <li>{@code waitDurationInOpenState}: not null, and longer than zero;</li>
     *   <li>{@code permittedCallsInHalfOpenState}: at least 1.</li>
     * </ul>
     */
    public static class CircuitBreakerProperties {

        /** Switches the breaker off (the store is then used as it is). */
        private boolean enabled = true;
        /** How many of the latest calls the failure rate is measured over. */
        private int slidingWindowSize = 20;
        /** Fewest calls before the failure rate is looked at at all. */
        private int minimumNumberOfCalls = 10;
        /** Percentage of failed calls (1 to 100) at or above which the breaker opens. */
        private int failureRateThreshold = 50;
        /** How long the breaker stays open before it lets a few probe calls through. */
        private Duration waitDurationInOpenState = Duration.ofSeconds(30);
        /** How many probe calls are allowed while half-open. */
        private int permittedCallsInHalfOpenState = 3;

        /** @return false if no breaker should be put around the store (default true) */
        public boolean isEnabled() {
            return enabled;
        }

        /** @param enabled false leaves the store unwrapped */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** @return size of the sliding window, in calls */
        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        /** @param slidingWindowSize at least 1 @throws IllegalArgumentException otherwise */
        public void setSlidingWindowSize(int slidingWindowSize) {
            if (slidingWindowSize < 1) {
                throw new IllegalArgumentException("idempotency.circuit-breaker.sliding-window-size must be at least 1 is invalid: " + slidingWindowSize);
            }
            this.slidingWindowSize = slidingWindowSize;
        }

        /** @return fewest calls before the failure rate counts */
        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        /** @param minimumNumberOfCalls at least 1 @throws IllegalArgumentException otherwise */
        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            if (minimumNumberOfCalls < 1) {
                throw new IllegalArgumentException("idempotency.circuit-breaker.minimum-number-of-calls must be at least 1 is invalid: " + minimumNumberOfCalls);
            }
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        /** @return failure percentage that opens the breaker */
        public int getFailureRateThreshold() {
            return failureRateThreshold;
        }

        /** @param failureRateThreshold 1 to 100 @throws IllegalArgumentException otherwise */
        public void setFailureRateThreshold(int failureRateThreshold) {
            if (failureRateThreshold < 1 || failureRateThreshold > 100) {
                throw new IllegalArgumentException("idempotency.circuit-breaker.failure-rate-threshold must be from 1 to 100 is invalid: " + failureRateThreshold);
            }
            this.failureRateThreshold = failureRateThreshold;
        }

        /** @return how long the breaker stays open */
        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        /** @param waitDurationInOpenState longer than zero @throws IllegalArgumentException if null, zero or negative */
        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            if (waitDurationInOpenState == null || waitDurationInOpenState.isZero() || waitDurationInOpenState.isNegative()) {
                throw new IllegalArgumentException("idempotency.circuit-breaker.wait-duration-in-open-state must be longer than zero is invalid: " + waitDurationInOpenState);
            }
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        /** @return how many probe calls half-open allows */
        public int getPermittedCallsInHalfOpenState() {
            return permittedCallsInHalfOpenState;
        }

        /** @param permittedCallsInHalfOpenState at least 1 @throws IllegalArgumentException otherwise */
        public void setPermittedCallsInHalfOpenState(int permittedCallsInHalfOpenState) {
            if (permittedCallsInHalfOpenState < 1) {
                throw new IllegalArgumentException("idempotency.circuit-breaker.permitted-calls-in-half-open-state must be at least 1 is invalid: " + permittedCallsInHalfOpenState);
            }
            this.permittedCallsInHalfOpenState = permittedCallsInHalfOpenState;
        }
    }
}
