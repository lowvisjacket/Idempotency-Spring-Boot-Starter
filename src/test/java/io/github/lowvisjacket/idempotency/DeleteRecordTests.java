package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;
import io.github.lowvisjacket.idempotency.model.Status;
import io.github.lowvisjacket.idempotency.store.IdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IdempotencyStore.delete, written to try to break it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public class DeleteRecordTests {

    private static final String KEY = "DELETE-123";

    @Autowired
    IdempotencyStore store;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearRedis() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private IdempotencyRecord record(String key, Duration ttl) {
        return new IdempotencyRecord(key, "fingerprint", "Test", "Hello World", 200, ttl);
    }

    // --- Basic behavior ---

    @Test
    void deletingExistingKeyReturnsTrueAndRemovesIt() {
        assertTrue(store.claim(record(KEY, Duration.ofMinutes(1))));

        assertTrue(store.delete(KEY));

        assertFalse(store.exists(KEY));
        assertTrue(store.find(KEY).isEmpty());
    }

    @Test
    void deletingMissingKeyReturnsFalse() {
        assertFalse(store.delete(KEY));
    }

    @Test
    void deletingTwiceReturnsTrueThenFalse() {
        store.claim(record(KEY, Duration.ofMinutes(1)));

        assertTrue(store.delete(KEY));
        assertFalse(store.delete(KEY));
    }

    @Test
    void deletingExpiredKeyReturnsFalse() throws InterruptedException {
        store.claim(record(KEY, Duration.ofSeconds(1)));
        Thread.sleep(1500);

        assertFalse(store.delete(KEY));
    }

    // --- The retry case: the reason delete exists ---

    @Test
    void keyCanBeClaimedAgainAfterDelete() {
        assertTrue(store.claim(record(KEY, Duration.ofMinutes(1))));
        assertFalse(store.claim(record(KEY, Duration.ofMinutes(1))));

        assertTrue(store.delete(KEY));

        assertTrue(store.claim(record(KEY, Duration.ofMinutes(1))));
    }

    @Test
    void reclaimAfterDeleteStartsFreshNotWithStaleData() {
        IdempotencyRecord completed = record(KEY, Duration.ofMinutes(1));
        completed.setStatus(Status.COMPLETED);
        completed.setBody("old response");
        store.claim(completed);
        store.delete(KEY);

        IdempotencyRecord fresh = new IdempotencyRecord(KEY, "new-fingerprint", null, null, 0, Duration.ofMinutes(1));
        assertTrue(store.claim(fresh));

        IdempotencyRecord stored = store.find(KEY).orElseThrow();
        assertEquals(Status.PENDING, stored.getStatus());
        assertEquals("new-fingerprint", stored.getFingerprint());
        assertNull(stored.getBody());
    }

    @Test
    void deletingCompletedRecordWorks() {
        store.claim(record(KEY, Duration.ofMinutes(1)));
        IdempotencyRecord completed = record(KEY, Duration.ofMinutes(1));
        completed.setStatus(Status.COMPLETED);
        assertTrue(store.complete(completed));

        assertTrue(store.delete(KEY));
        assertFalse(store.exists(KEY));
    }

    @Test
    void lateCompletionAfterDeleteDoesNotResurrectTheKey() {
        // A request fails (5xx) and its key is deleted, then a slow completion arrives.
        store.claim(record(KEY, Duration.ofMinutes(1)));
        store.delete(KEY);

        IdempotencyRecord late = record(KEY, Duration.ofMinutes(1));
        late.setStatus(Status.COMPLETED);

        assertFalse(store.complete(late));
        assertFalse(store.exists(KEY));
    }

    // --- Isolation: delete must only touch the key it was given ---

    @Test
    void deletingOneKeyLeavesOtherKeysAlone() {
        store.claim(record("KEY-A", Duration.ofMinutes(1)));
        store.claim(record("KEY-B", Duration.ofMinutes(1)));

        assertTrue(store.delete("KEY-A"));

        assertFalse(store.exists("KEY-A"));
        assertTrue(store.exists("KEY-B"));
    }

    @Test
    void globCharactersAreTreatedAsPlainTextNotPatterns() {
        store.claim(record("KEY-A", Duration.ofMinutes(1)));
        store.claim(record("KEY-B", Duration.ofMinutes(1)));

        assertFalse(store.delete("KEY-*"));
        assertFalse(store.delete("*"));
        assertFalse(store.delete("KEY-?"));
        assertFalse(store.delete("KEY-[AB]"));

        assertTrue(store.exists("KEY-A"));
        assertTrue(store.exists("KEY-B"));
    }

    @Test
    void keyMatchingIsExactAndCaseSensitive() {
        store.claim(record("Key-A", Duration.ofMinutes(1)));

        assertFalse(store.delete("key-a"));
        assertFalse(store.delete("Key-A "));
        assertTrue(store.exists("Key-A"));
    }

    @Test
    void unusualButValidKeysCanBeDeleted() {
        List<String> keys = List.of(
                "with space",
                "unicode-ключ-鍵-🔑",
                "line\nbreak",
                "quote\"and'apostrophe",
                "colon:separated:key",
                "x".repeat(1024));

        for (String key : keys) {
            assertTrue(store.claim(record(key, Duration.ofMinutes(1))), "claim failed for: " + key);
            assertTrue(store.delete(key), "delete failed for: " + key);
            assertFalse(store.exists(key), "key still exists: " + key);
        }
    }

    @Test
    void keyWithNoExpiryCanBeDeleted() {
        redisTemplate.opsForValue().set(KEY, "{}");

        assertTrue(store.delete(KEY));
        assertFalse(store.exists(KEY));
    }

    // --- Garbage in ---

    @Test
    void emptyKeyReturnsFalseWithoutAffectingOtherKeys() {
        store.claim(record(KEY, Duration.ofMinutes(1)));

        assertFalse(store.delete(""));

        assertTrue(store.exists(KEY));
    }

    @Test
    void nullKeyFailsLoudlyInsteadOfSilentlySucceeding() {
        assertThrows(Exception.class, () -> store.delete(null));
    }

    // --- Concurrency ---

    @Test
    void whenManyThreadsDeleteTheSameKeyExactlyOneWins() throws Exception {
        int threads = 16;
        store.claim(record(KEY, Duration.ofMinutes(1)));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit((Callable<Boolean>) () -> {
                        start.await();
                        return store.delete(KEY);
                    }))
                    .toList();
            start.countDown();

            long winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) winners++;
            }

            assertEquals(1, winners, "exactly one delete should report success");
            assertFalse(store.exists(KEY));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void whenDeleteRacesWithReclaimTheKeyEndsUpClaimedOrFreeNeverCorrupted() throws Exception {
        // Repeatedly: delete and re-claim at the same time. Whatever the order,
        // the stored value must be a valid PENDING record or the key must be absent.
        for (int round = 0; round < 50; round++) {
            store.delete(KEY);
            store.claim(record(KEY, Duration.ofMinutes(1)));

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<Boolean> delete = pool.submit(() -> {
                    start.await();
                    return store.delete(KEY);
                });
                Future<Boolean> claim = pool.submit(() -> {
                    start.await();
                    return store.claim(record(KEY, Duration.ofMinutes(1)));
                });
                start.countDown();
                delete.get();
                claim.get();
            } finally {
                pool.shutdownNow();
            }

            store.find(KEY).ifPresent(stored -> assertEquals(Status.PENDING, stored.getStatus()));
        }
    }
}
