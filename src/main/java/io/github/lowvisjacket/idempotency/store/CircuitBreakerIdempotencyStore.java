package io.github.lowvisjacket.idempotency.store;

import io.github.lowvisjacket.idempotency.exception.StoreUnavailableException;
import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Wraps another {@link IdempotencyStore} in a circuit breaker, so a store that is down is not called over and over.
 *
 * <p>Without it, every request waits for the broken store to time out (and ties up a thread while it does), then
 * gets a 503. With it, after a few failures the breaker <em>opens</em> and calls are refused at once, without
 * touching the store, until the store has had time to recover.
 *
 * <h2>The three states (kept by the {@link CircuitBreaker}, not by this class)</h2>
 * <ul>
 *   <li><b>CLOSED</b> (normal): calls go to the delegate; their outcome is recorded. When enough of them fail, the
 *       breaker opens.</li>
 *   <li><b>OPEN</b>: calls are refused with {@link StoreUnavailableException}; the delegate is not touched. After
 *       the breaker's wait time it moves to half-open.</li>
 *   <li><b>HALF_OPEN</b>: a limited number of probe calls go to the delegate. If they succeed the breaker closes; if
 *       one fails it opens again. Calls beyond the allowed probes are refused like in OPEN.</li>
 * </ul>
 *
 * <h2>Invariants (true before and after every method call)</h2>
 * <ol>
 *   <li><b>Transparent when closed.</b> The decorator adds no behaviour of its own: arguments reach the delegate
 *       unchanged (same instances, {@code null} included, validation is the delegate's job), and the delegate's
 *       answer comes back unchanged (same instance).</li>
 *   <li><b>One call, one attempt.</b> A decorator call makes at most one delegate call: no retries, no second
 *       attempts, no extra "check" calls.</li>
 *   <li><b>One breaker for the whole store.</b> All six operations share the same {@link CircuitBreaker}, so
 *       failures of any operation count toward opening it, and an open breaker refuses all of them.</li>
 *   <li><b>Only exceptions are failures.</b> A {@code false} or empty answer ("claim lost", "not found") is a
 *       normal answer and counts as a success. What counts as a failure beyond that is decided by the breaker's own
 *       configuration (which exceptions it records or ignores), not by this class.</li>
 *   <li><b>No store details leak.</b> Refusals use {@link StoreUnavailableException} with a fixed, safe message; the
 *       text of an earlier store failure never appears in it.</li>
 *   <li><b>Thread-safe.</b> Any number of threads may call at once. The class keeps no mutable state of its own.</li>
 * </ol>
 *
 * <h2>Pre- and postconditions common to all six operations</h2>
 * Each method's own Javadoc adds what is specific to it. For a call to {@code op(args)}:
 * <table>
 *   <caption>Outcome by breaker state</caption>
 *   <tr><th>Precondition</th><th>Postcondition</th></tr>
 *   <tr><td>The breaker permits the call (CLOSED, or HALF_OPEN with a probe available) and the delegate
 *       answers</td><td>The delegate was called exactly once with {@code args}; its answer is returned unchanged;
 *       one success is recorded on the breaker.</td></tr>
 *   <tr><td>The breaker permits the call and the delegate throws {@code e}</td><td>The delegate was called exactly
 *       once; {@code e} is rethrown <em>unchanged</em> (the same instance, not wrapped); the breaker records the
 *       failure (unless its configuration ignores that exception type).</td></tr>
 *   <tr><td>The breaker refuses the call (OPEN, or HALF_OPEN with no probe left)</td><td>The delegate was <b>not</b>
 *       called; a {@link StoreUnavailableException} is thrown whose cause is the breaker's refusal; the refusal is
 *       counted as "not permitted", <em>not</em> as a failure.</td></tr>
 * </table>
 *
 * @see CircuitBreaker
 * @see StoreUnavailableException
 */
public class CircuitBreakerIdempotencyStore implements IdempotencyStore {

    private IdempotencyStore idempotencyStore;
    private CircuitBreaker circuitBreaker;

    /**
     * Creates the decorator. Nothing is called or changed during construction.
     *
     * <p><b>Preconditions:</b> {@code delegate} and {@code circuitBreaker} are not null. The breaker may be in any
     * state and may be shared with other code.
     *
     * <p><b>Postconditions:</b> the delegate has not been called; the breaker's state and counters are unchanged;
     * the decorator now uses exactly these two objects (not copies).
     *
     * @param delegate       the real store
     * @param circuitBreaker the breaker that guards every call to it
     * @throws NullPointerException if either argument is null
     */
    public CircuitBreakerIdempotencyStore(IdempotencyStore delegate, CircuitBreaker circuitBreaker) {
        this.idempotencyStore = Objects.requireNonNull(delegate);
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker);
    }

    /**
     * Guarded {@link IdempotencyStore#claim(IdempotencyRecord)}.
     *
     * <p><b>Preconditions:</b> as for the interface (the record is the caller's business, {@code null} is passed
     * straight to the delegate); the breaker permits the call.
     *
     * <p><b>Postconditions:</b> the delegate's {@code claim} was called once with the same record instance and its
     * boolean is returned. {@code false} ("another request owns the key") is a success, not a failure.
     *
     * @param record the record to claim
     * @return the delegate's answer
     * @throws StoreUnavailableException if the breaker refuses the call (the delegate is not called)
     * @throws RuntimeException          whatever the delegate threw, unchanged
     */
    @Override
    public boolean claim(IdempotencyRecord record) {
        return guard(() -> idempotencyStore.claim(record));
    }

    /**
     * Guarded {@link IdempotencyStore#find(String)}.
     *
     * <p><b>Preconditions:</b> as for {@link #claim}.
     *
     * <p><b>Postconditions:</b> the delegate's {@code find} was called once with the same key; the very same
     * {@link Optional} instance is returned. An empty Optional is a success.
     *
     * @param key the stored key
     * @return the delegate's answer
     * @throws StoreUnavailableException if the breaker refuses the call (the delegate is not called)
     * @throws RuntimeException          whatever the delegate threw, unchanged
     */
    @Override
    public Optional<IdempotencyRecord> find(String key) {
        return guard(() -> idempotencyStore.find(key));
    }

    /**
     * Guarded {@link IdempotencyStore#complete(IdempotencyRecord)}.
     *
     * <p><b>Preconditions:</b> as for {@link #claim}.
     *
     * <p><b>Postconditions:</b> the delegate's {@code complete} was called once with the same record instance and its
     * boolean is returned. {@code false} ("the key had expired") is a success, not a failure.
     *
     * @param record the finished record
     * @return the delegate's answer
     * @throws StoreUnavailableException if the breaker refuses the call (the delegate is not called)
     * @throws RuntimeException          whatever the delegate threw, unchanged
     */
    @Override
    public boolean complete(IdempotencyRecord record) {
        return guard(() -> idempotencyStore.complete(record));
    }

    /**
     * Guarded {@link IdempotencyStore#delete(String)}.
     *
     * <p><b>Preconditions:</b> as for {@link #claim}.
     *
     * <p><b>Postconditions:</b> the delegate's {@code delete} was called once with the same key and its boolean is
     * returned. {@code false} ("nothing to delete") is a success, not a failure.
     *
     * @param key the stored key
     * @return the delegate's answer
     * @throws StoreUnavailableException if the breaker refuses the call (the delegate is not called)
     * @throws RuntimeException          whatever the delegate threw, unchanged
     */
    @Override
    public boolean delete(String key) {
        return guard(() -> idempotencyStore.delete(key));
    }

    /** Guarded {@link IdempotencyStore#delete(String, String)}; same contract as {@link #delete(String)}. */
    @Override
    public boolean delete(String key, String owner) {
        return guard(() -> idempotencyStore.delete(key, owner));
    }

    /**
     * Guarded {@link IdempotencyStore#exists(String)}.
     *
     * <p><b>Preconditions:</b> as for {@link #claim}.
     *
     * <p><b>Postconditions:</b> the delegate's {@code exists} was called once with the same key and its boolean is
     * returned. {@code false} is a success, not a failure.
     *
     * @param key the stored key
     * @return the delegate's answer
     * @throws StoreUnavailableException if the breaker refuses the call (the delegate is not called)
     * @throws RuntimeException          whatever the delegate threw, unchanged
     */
    @Override
    public boolean exists(String key) {
        return guard(() -> idempotencyStore.exists(key));
    }

    /**
     * Guarded {@link IdempotencyStore#getRemainingTtl(String)}.
     *
     * <p><b>Preconditions:</b> as for {@link #claim}.
     *
     * <p><b>Postconditions:</b> the delegate's {@code getRemainingTtl} was called once with the same key; the very
     * same {@link Optional} instance is returned. An empty Optional is a success.
     *
     * @param key the stored key
     * @return the delegate's answer
     * @throws StoreUnavailableException if the breaker refuses the call (the delegate is not called)
     * @throws RuntimeException          whatever the delegate threw, unchanged
     */
    @Override
    public Optional<Duration> getRemainingTtl(String key) {
        return guard(() -> idempotencyStore.getRemainingTtl(key));
    }

    private <T> T guard(Supplier<T> call) {
        try {
            return circuitBreaker.executeSupplier(call);
        } catch (CallNotPermittedException e) {
            throw new StoreUnavailableException("The idempotency store is temporarily unavailable", e);
        }
    }
}
