package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.annotation.Idempotent;
import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;
import io.github.lowvisjacket.idempotency.model.Status;
import io.github.lowvisjacket.idempotency.store.IdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F04: a record has no owner, so a request that outlives its own record can complete or delete the record of the
 * request that claimed the key after it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, OwnershipTokenTests.Api.class})
class OwnershipTokenTests {

    @Value("${local.server.port}") int port;
    @Autowired Api api;
    @Autowired IdempotencyStore store;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void reset() {
        redis.execute((RedisCallback<Void>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
        api.runs.set(0);
    }

    private IdempotencyRecord record(String key, String owner, String fingerprint) {
        IdempotencyRecord r = new IdempotencyRecord(key, fingerprint, null, null, 0, Duration.ofMinutes(5));
        r.setOwner(owner);
        return r;
    }

    private IdempotencyRecord done(String key, String owner, String fingerprint) {
        IdempotencyRecord r = new IdempotencyRecord(key, fingerprint, "text/plain", null, "body", 200, Duration.ofMinutes(5));
        r.setOwner(owner);
        r.setStatus(Status.COMPLETED);
        return r;
    }

    @Test
    void F04_aStaleOwnerCannotCompleteTheNewOwnersRecord() {
        assertTrue(store.claim(record("k1", "owner-2", "fp-2")));

        assertFalse(store.complete(done("k1", "owner-1", "fp-1")));

        IdempotencyRecord stored = store.find("k1").orElseThrow();
        assertEquals(Status.PENDING, stored.getStatus());
        assertEquals("fp-2", stored.getFingerprint());
    }

    @Test
    void F04_aStaleOwnerCannotDeleteTheNewOwnersRecord() {
        assertTrue(store.claim(record("k2", "owner-2", "fp-2")));

        assertFalse(store.delete("k2", "owner-1"));

        assertTrue(store.exists("k2"));
    }

    @Test
    void F04_theOwnerCanCompleteAndDelete() {
        assertTrue(store.claim(record("k3", "owner-1", "fp-1")));
        assertTrue(store.complete(done("k3", "owner-1", "fp-1")));
        assertEquals(Status.COMPLETED, store.find("k3").orElseThrow().getStatus());
        assertTrue(store.delete("k3", "owner-1"));
        assertFalse(store.exists("k3"));
    }

    private CompletableFuture<HttpResponse<String>> send(String key) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/slow"))
                .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.noBody()).build();
        return HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Request 1 outlives its 2 s record and then fails; request 2 claims the key after the expiry. Request 1's failure
     * must not release request 2's key, or request 3 runs the handler a third time while request 2 is still working.
     */
    @Test
    void F04_aSlowRequestThatFailsDoesNotReleaseTheKeyOfTheRequestThatReplacedIt() throws Exception {
        String key = "slow-" + System.nanoTime();
        CompletableFuture<HttpResponse<String>> first = send(key);
        Thread.sleep(2300);
        CompletableFuture<HttpResponse<String>> second = send(key);
        Thread.sleep(1000);
        first.get();
        HttpResponse<String> third = send(key).get();
        second.get();

        assertEquals(409, third.statusCode(), third.body());
        assertEquals(2, api.runs.get(), "the handler ran " + api.runs.get() + " times for one key");
    }

    @RestController
    static class Api {
        final AtomicInteger runs = new AtomicInteger();

        @Idempotent(ttl = "2s")
        @PostMapping("/slow")
        String slow() throws Exception {
            int run = runs.incrementAndGet();
            if (run == 1) {
                Thread.sleep(3000);
                throw new IllegalStateException("first run fails after the record expired");
            }
            Thread.sleep(2000);
            return "run-" + run;
        }
    }
}
