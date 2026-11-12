package io.github.lowvisjacket.idempotency.config;

import java.time.Duration;
import java.util.List;

/**
 * The one place for values that more than one class needs (sizes, names, defaults).
 * Later these become the defaults of configuration properties, so users can override them.
 */
public final class IdempotencyDefaults {

    private IdempotencyDefaults() {
    }

    /** Largest request body (in bytes) the library will buffer for fingerprinting. */
    public static final int MAX_BODY_SIZE_BYTES = 2 * 1024;

    /** Longest idempotency key (in characters) a client may send. */
    public static final int MAX_KEY_LENGTH = 255;

    /**
     * Largest response body (in bytes) the library holds in memory so it can be saved and replayed. A bigger
     * response is still sent to the client in full, but it cannot be saved.
     */
    public static final int MAX_RESPONSE_SIZE_BYTES = 64 * 1024;

    /** Default request header that carries the client's idempotency key. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /**
     * Request attribute the filter sets (to Boolean.TRUE) when the body was over the cap and was therefore
     * NOT buffered, so the interceptor can decide what to do. Namespaced to avoid clashing with an app's own attributes.
     */
    public static final String BODY_EXCEEDS_CAP_ATTRIBUTE = "io.github.lowvisjacket.idempotency.BODY_EXCEEDS_CAP";

    /**
     * Request attribute the interceptor sets, to the {@code PENDING} record it just claimed, when a request wins its
     * claim. The response filter reads it after the controller has run to know which record to complete or delete.
     * Absent means "this request did not claim anything" (plain endpoint, refusal, or replay).
     */
    public static final String CLAIM_ATTRIBUTE = "io.github.lowvisjacket.idempotency.CLAIM";

    /**
     * The only response headers saved with a record and replayed, spelled the way they are stored and sent. Anything
     * else (Set-Cookie, Authorization-like values, Date, Content-Length, per-request ids) is deliberately not kept:
     * it is either private, or wrong for a later request. Content-Type has its own field.
     */
    public static final List<String> REPLAYED_RESPONSE_HEADERS =
            List.of("Location", "Content-Location", "ETag", "Content-Language");

    /** Response header added to every replayed response, with the value {@code true}, so clients can tell a replay from a first run. */
    /**
     * Statuses that say "not now" or "not you" rather than "this is the outcome of your request". They are never
     * saved: a retry must be judged afresh, not told for the whole TTL that the caller is forbidden or throttled.
     */
    public static final java.util.Set<Integer> NEVER_SAVED_STATUSES = java.util.Set.of(401, 403, 407, 408, 425, 429);

    public static final String REPLAY_HEADER = "Idempotency-Replayed";

    /**
     * Order of the response-capturing filter. Lower runs first (outer). Spring Security's filter chain is at -100, so
     * this runs after it: a request Security already rejected never reaches the library.
     */
    public static final int RESPONSE_FILTER_ORDER = -50;

    /** Order of the request-body-caching filter; one step inside the response filter. */
    public static final int REQUEST_FILTER_ORDER = -49;

    /** Start of every key this library writes to the store. The "v1" lets the key format change safely later. */
    public static final String KEY_PREFIX = "idempotency:v1:";

    /** Segment that follows the prefix for requests made by an identified caller (then the caller hash, then the key). */
    public static final String USER_SEGMENT = "user";

    /** Segment that follows the prefix for requests with no identified caller (then the key). */
    public static final String ANONYMOUS_SEGMENT = "anonymous";

    /** {@code Retry-After} sent with a 409 (a request with this key is still running). */
    public static final Duration PENDING_RETRY_AFTER = Duration.ofSeconds(1);

    /** {@code Retry-After} sent with a 503 (the store cannot be reached). */
    public static final Duration STORE_RETRY_AFTER = Duration.ofSeconds(5);

    /** Default time a stored record is kept, in the same text format @Idempotent(ttl = ...) accepts. */
    public static final String DEFAULT_TTL = "24h";

    /** Longest time a stored record may be kept. A TTL above this is rejected. */
    public static final Duration MAX_TTL = Duration.ofDays(365);
}
