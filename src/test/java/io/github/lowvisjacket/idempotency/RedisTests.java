package io.github.lowvisjacket.idempotency;


import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;
import io.github.lowvisjacket.idempotency.store.RedisLookup;
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
    RedisLookup redisLookup;

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
        assertTrue(redisLookup.addKeyIfAbsent(pendingRecord(Duration.ofDays(1))));
    }

    @Test
    void claimingExistingKeyReturnsFalse() {
        redisLookup.addKeyIfAbsent(pendingRecord(Duration.ofDays(1)));
        assertFalse(redisLookup.addKeyIfAbsent(pendingRecord(Duration.ofDays(1))));
    }

    @Test
    void expiryReturnsTrue() throws InterruptedException {
        assertTrue(redisLookup.addKeyIfAbsent(pendingRecord(Duration.ofSeconds(2))));
        Thread.sleep(3000);
        assertTrue(redisLookup.addKeyIfAbsent(pendingRecord(Duration.ofSeconds(2))));
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
        assertTrue(redisLookup.addKeyIfAbsent(record));

        IdempotencyRecord after = redisLookup.getIdempotencyRecord(redisLookup.getIdempotencyRecordJSON(KEY));

        assertEquals(Status.PENDING, after.getStatus());
        assertEquals("fingerprint", after.getFingerprint());
        assertEquals("Test", after.getHeader());
        assertEquals("Hello World", after.getBody());
        assertEquals(200, after.getHttpStatus());
    }

    @Test
    void completingKeyStoresCompletedRecord() {
        assertTrue(redisLookup.addKeyIfAbsent(pendingRecord(Duration.ofSeconds(10))));

        assertTrue(redisLookup.completeRecord(responseRecord(Duration.ofSeconds(10))));

        IdempotencyRecord after = redisLookup.getIdempotencyRecord(redisLookup.getIdempotencyRecordJSON(KEY));
        assertEquals(Status.COMPLETED, after.getStatus());
        assertEquals("Hello World", after.getBody());
        assertEquals(200, after.getHttpStatus());
    }

    @Test
    void completingKeyResetsTTL() {
        assertTrue(redisLookup.addKeyIfAbsent(pendingRecord(Duration.ofSeconds(5))));

        assertTrue(redisLookup.completeRecord(responseRecord(Duration.ofSeconds(100))));

        Long ttl = redisLookup.getTTLFromKey(KEY);
        assertTrue(ttl > 90 && ttl <= 100, "expected TTL near 100 but was " + ttl);
    }

    @Test
    void completingMissingKeyReturnsFalseAndCreatesNothing() {
        assertFalse(redisLookup.completeRecord(responseRecord(Duration.ofSeconds(100))));

        assertFalse(redisLookup.keyExists(KEY));
    }

}
