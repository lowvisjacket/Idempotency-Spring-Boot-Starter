package io.github.lowvisjacket.idempotency.config;

import java.time.Duration;

/**
 * The one place for values that more than one class needs (sizes, names, defaults).
 * Later these become the defaults of configuration properties, so users can override them.
 */
public final class IdempotencyDefaults {

    private IdempotencyDefaults() {
        // Constants only; never instantiated.
    }

    // --- Sizes ---

    /** Largest request body (in bytes) the library will buffer for fingerprinting. */
    public static final int MAX_BODY_SIZE_BYTES = 2 * 1024;

    /** Longest idempotency key (in characters) a client may send. */
    public static final int MAX_KEY_LENGTH = 255;

    // --- Names ---

    /** Default request header that carries the client's idempotency key. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /**
     * Request attribute the filter sets (to Boolean.TRUE) when the body was over the cap and was therefore
     * NOT buffered, so the interceptor can decide what to do. Namespaced to avoid clashing with an app's own attributes.
     */
    public static final String BODY_EXCEEDS_CAP_ATTRIBUTE = "io.github.lowvisjacket.idempotency.BODY_EXCEEDS_CAP";

    /** Response header added to every replayed response, with the value {@code true}, so clients can tell a replay from a first run. */
    public static final String REPLAY_HEADER = "Idempotency-Replayed";

    // --- Stored keys ---

    /** Start of every key this library writes to the store. The "v1" lets the key format change safely later. */
    public static final String KEY_PREFIX = "idempotency:v1:";

    /** Segment that follows the prefix for requests made by an identified caller (then the caller hash, then the key). */
    public static final String USER_SEGMENT = "user";

    /** Segment that follows the prefix for requests with no identified caller (then the key). */
    public static final String ANONYMOUS_SEGMENT = "anonymous";

    // --- Time ---

    /** {@code Retry-After} sent with a 409 (a request with this key is still running). */
    public static final Duration PENDING_RETRY_AFTER = Duration.ofSeconds(1);

    /** {@code Retry-After} sent with a 503 (the store cannot be reached). */
    public static final Duration STORE_RETRY_AFTER = Duration.ofSeconds(5);

    /** Default time a stored record is kept, in the same text format @Idempotent(ttl = ...) accepts. */
    public static final String DEFAULT_TTL = "24h";

    /** Longest time a stored record may be kept. A TTL above this is rejected. */
    public static final Duration MAX_TTL = Duration.ofDays(365);
}
