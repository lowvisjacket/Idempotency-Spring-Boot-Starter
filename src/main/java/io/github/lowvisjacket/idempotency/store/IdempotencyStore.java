package io.github.lowvisjacket.idempotency.store;

import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;

import java.time.Duration;
import java.util.Optional;

/**
 * Storage for idempotency records. Implementations must make {@link #claim} atomic:
 * when several callers claim the same key at once, exactly one gets {@code true}.
 */
public interface IdempotencyStore {

    /**
     * Atomically stores the record under its key, with its TTL, only if the key is not already taken.
     *
     * @return true if this call took the key, false if it was already taken
     */
    boolean claim(IdempotencyRecord record);

    /**
     * Looks up the record stored under a key. The returned record has its key set;
     * its TTL is not populated (use {@link #getRemainingTtl}).
     */
    Optional<IdempotencyRecord> find(String key);

    /**
     * Overwrites an existing record and resets its TTL to the record's TTL.
     * Does nothing if the key no longer exists (for example, it expired mid-request).
     *
     * @return true if the record was overwritten, false if the key was missing
     */
    boolean complete(IdempotencyRecord record);

    /**
     * Removes a key so it can be claimed again.
     *
     * @return true if a key was removed, false if it did not exist
     */
    boolean delete(String key);

    /** Whether a record is currently stored under the key. */
    boolean exists(String key);

    /** Time left before the key expires; empty if the key is missing or has no expiry. */
    Optional<Duration> getRemainingTtl(String key);
}
