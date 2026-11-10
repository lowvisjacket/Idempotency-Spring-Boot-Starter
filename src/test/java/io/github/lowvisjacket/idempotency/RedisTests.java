package io.github.lowvisjacket.idempotency;


import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;
import io.github.lowvisjacket.idempotency.store.IdempotencyStore;
import io.github.lowvisjacket.idempotency.model.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
public class RedisTests {

    private static final String KEY = "TEST-123";

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

    private IdempotencyRecord pendingRecord(Duration ttl) {
        return new IdempotencyRecord(KEY, "fingerprint", null, null, 200, ttl);
    }

    @Test
    void claimingNewKeyReturnsTrue() {
        assertTrue(store.claim(pendingRecord(Duration.ofDays(1))));
    }

    @Test
    void claimingExistingKeyReturnsFalse() {
        store.claim(pendingRecord(Duration.ofDays(1)));
        assertFalse(store.claim(pendingRecord(Duration.ofDays(1))));
    }

    @Test
    void expiryReturnsTrue() throws InterruptedException {
        assertTrue(store.claim(pendingRecord(Duration.ofSeconds(2))));
        Thread.sleep(3000);
        assertTrue(store.claim(pendingRecord(Duration.ofSeconds(2))));
    }

    private IdempotencyRecord responseRecord(Duration ttl) {
        IdempotencyRecord record = new IdempotencyRecord(KEY, "fingerprint", "Test", "Hello World", 200, ttl);
        record.setStatus(Status.COMPLETED);
        return record;
    }

    @Test
    void storedJsonReadsBackWithAllFields() {
        IdempotencyRecord record = pendingRecord(Duration.ofSeconds(10));
        record.setHeader("Test");
        record.setBody("Hello World");
        assertTrue(store.claim(record));

        IdempotencyRecord after = store.find(KEY).orElseThrow();

        assertEquals(Status.PENDING, after.getStatus());
        assertEquals("fingerprint", after.getFingerprint());
        assertEquals("Test", after.getHeader());
        assertEquals("Hello World", after.getBody());
        assertEquals(200, after.getHttpStatus());
    }

    @Test
    void completingKeyStoresCompletedRecord() {
        assertTrue(store.claim(pendingRecord(Duration.ofSeconds(10))));

        assertTrue(store.complete(responseRecord(Duration.ofSeconds(10))));

        IdempotencyRecord after = store.find(KEY).orElseThrow();
        assertEquals(Status.COMPLETED, after.getStatus());
        assertEquals("Hello World", after.getBody());
        assertEquals(200, after.getHttpStatus());
    }

    @Test
    void completingKeyResetsTTL() {
        assertTrue(store.claim(pendingRecord(Duration.ofSeconds(5))));

        assertTrue(store.complete(responseRecord(Duration.ofSeconds(100))));

        long ttl = store.getRemainingTtl(KEY).orElseThrow().toSeconds();
        assertTrue(ttl > 90 && ttl <= 100, "expected TTL near 100 but was " + ttl);
    }

    @Test
    void findReturnsRecordWithKeyPopulated() {
        store.claim(pendingRecord(Duration.ofSeconds(10)));

        assertEquals(KEY, store.find(KEY).orElseThrow().getKey());
    }

    @Test
    void findOnMissingKeyReturnsEmpty() {
        assertTrue(store.find(KEY).isEmpty());
    }

    @Test
    void existsReflectsWhetherKeyIsStored() {
        assertFalse(store.exists(KEY));

        store.claim(pendingRecord(Duration.ofSeconds(10)));

        assertTrue(store.exists(KEY));
    }

    @Test
    void remainingTtlIsEmptyForMissingKey() {
        assertTrue(store.getRemainingTtl(KEY).isEmpty());
    }

    @Test
    void remainingTtlIsEmptyForKeyWithNoExpiry() {
        redisTemplate.opsForValue().set(KEY, "{}");

        assertTrue(store.getRemainingTtl(KEY).isEmpty());
    }

    @Test
    void completingMissingKeyReturnsFalseAndCreatesNothing() {
        assertFalse(store.complete(responseRecord(Duration.ofSeconds(100))));

        assertFalse(store.exists(KEY));
    }

}
