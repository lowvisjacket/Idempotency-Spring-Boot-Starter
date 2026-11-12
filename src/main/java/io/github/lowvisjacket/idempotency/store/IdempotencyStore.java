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

    /**
     * Removes the record only if it was claimed by the request holding {@code owner}. A request that no longer owns
     * the key (its record expired and another request claimed it) must not remove the new owner's record.
     *
     * <p>The default ignores the owner and calls {@link #delete(String)}, so a store written before owners existed
     * keeps working; a store that can compare and delete atomically should override it.
     *
     * @param key   the stored key
     * @param owner the owner token from the claimed record
     * @return true if a record was removed
     */
    default boolean delete(String key, String owner) {
        return delete(key);
    }

    /** Whether a record is currently stored under the key. */
    boolean exists(String key);

    /** Time left before the key expires; empty if the key is missing or has no expiry. */
    Optional<Duration> getRemainingTtl(String key);
}
