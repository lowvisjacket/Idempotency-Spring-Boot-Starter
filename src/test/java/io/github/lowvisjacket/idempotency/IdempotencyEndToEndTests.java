package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.annotation.Idempotent;
import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;
import io.github.lowvisjacket.idempotency.model.Status;
import io.github.lowvisjacket.idempotency.store.IdempotencyStore;
import io.github.lowvisjacket.idempotency.store.StoredKeys;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The whole library in a real Spring Boot application with a real Redis (Testcontainers): nothing is wired by hand,
 * so these only pass if the auto-configuration does its job. Each test uses a fresh key.
 */
@SpringBootTest(properties = "idempotency.max-response-body-bytes=200")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, IdempotencyEndToEndTests.Api.class})
class IdempotencyEndToEndTests {

    private static final String HEADER = "Idempotency-Key";
    private static final String JSON = "{\"item\":\"book\"}";

    @Autowired MockMvc mvc;
    @Autowired Api api;
    @Autowired IdempotencyStore store;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void reset() {
        redis.execute((RedisCallback<Void>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
        api.reset();
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static MockHttpServletRequestBuilder order(String key, String body) {
        return post("/orders").header(HEADER, key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MvcResult send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    // ---- the happy path and replay

    @Test
    void firstRequestRunsTheControllerAndGetsItsAnswer() throws Exception {
        MvcResult result = send(order(newKey(), JSON));
        assertEquals(201, result.getResponse().getStatus());
        assertEquals("order-1", result.getResponse().getContentAsString());
        assertNull(result.getResponse().getHeader("Idempotency-Replayed"));
        assertEquals(1, api.orders.get());
    }

    @Test
    void controllerStillReceivesTheWholeRequestBody() throws Exception {
        send(order(newKey(), JSON));
        assertEquals(JSON, api.lastBody);
    }

    @Test
    void sameKeyAndSameRequestIsAnsweredFromTheSavedResponse() throws Exception {
        String key = newKey();
        MvcResult first = send(order(key, JSON));
        MvcResult second = send(order(key, JSON));

        assertEquals(201, second.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(), second.getResponse().getContentAsString());
        assertEquals("true", second.getResponse().getHeader("Idempotency-Replayed"));
        assertEquals(1, api.orders.get(), "the controller must not run again");
    }

    @Test
    void manyRetriesAllGetTheSameAnswer() throws Exception {
        String key = newKey();
        send(order(key, JSON));
        for (int i = 0; i < 10; i++) {
            MvcResult retry = send(order(key, JSON));
            assertEquals("order-1", retry.getResponse().getContentAsString());
            assertEquals(201, retry.getResponse().getStatus());
        }
        assertEquals(1, api.orders.get());
    }

    @Test
    void anImmediateRetryNeverSeesAnInProgressAnswer() throws Exception {
        // The record must be COMPLETED before the client can see the response.
        for (int i = 0; i < 25; i++) {
            String key = newKey();
            send(order(key, JSON));
            assertNotEquals(409, send(order(key, JSON)).getResponse().getStatus(), "round " + i);
        }
    }

    @Test
    void differentKeysAreIndependent() throws Exception {
        send(order(newKey(), JSON));
        MvcResult second = send(order(newKey(), JSON));
        assertEquals("order-2", second.getResponse().getContentAsString());
        assertEquals(2, api.orders.get());
    }

    @Test
    void unicodeResponseIsReplayedExactly() throws Exception {
        String key = newKey();
        MvcResult first = send(post("/unicode").header(HEADER, key));
        MvcResult second = send(post("/unicode").header(HEADER, key));
        assertEquals("héllo ✓ 😀", new String(first.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        assertArrayEquals(first.getResponse().getContentAsByteArray(), second.getResponse().getContentAsByteArray());
    }

    @Test
    void aReplayHasTheSameContentTypeAsTheFirstAnswer() throws Exception {
        String key = newKey();
        MvcResult first = send(post("/json").header(HEADER, key));
        MvcResult second = send(post("/json").header(HEADER, key));

        assertNotNull(first.getResponse().getContentType());
        assertTrue(first.getResponse().getContentType().startsWith("application/json"));
        assertEquals(first.getResponse().getContentType(), second.getResponse().getContentType());
        assertEquals(first.getResponse().getContentAsString(), second.getResponse().getContentAsString());
        assertEquals("true", second.getResponse().getHeader("Idempotency-Replayed"));
        assertEquals(1, api.jsons.get());
    }

    @Test
    void aTextReplayHasTheSameContentTypeAsTheFirstAnswerToo() throws Exception {
        String key = newKey();
        MvcResult first = send(post("/unicode").header(HEADER, key));
        MvcResult second = send(post("/unicode").header(HEADER, key));

        assertEquals(first.getResponse().getContentType(), second.getResponse().getContentType());
        assertTrue(second.getResponse().getContentType().toLowerCase().contains("utf-8"));
    }

    @Test
    void theSavedRecordRemembersTheContentType() throws Exception {
        String key = newKey();
        MvcResult first = send(post("/json").header(HEADER, key));

        assertEquals(first.getResponse().getContentType(),
                store.find(StoredKeys.build(null, key)).orElseThrow().getContentType());
    }

    @Test
    void anEmptyResponseIsReplayedAsEmpty() throws Exception {
        String key = newKey();
        send(post("/empty").header(HEADER, key));
        MvcResult second = send(post("/empty").header(HEADER, key));
        assertEquals(204, second.getResponse().getStatus());
        assertEquals("", second.getResponse().getContentAsString());
        assertEquals("true", second.getResponse().getHeader("Idempotency-Replayed"));
        assertEquals(1, api.empties.get());
    }

    // ---- what is saved

    @Test
    void theSavedRecordIsCompletedWithTheStatusBodyAndTheAnnotationsTtl() throws Exception {
        String key = newKey();
        send(order(key, JSON));

        String storedKey = StoredKeys.build(null, key);
        IdempotencyRecord saved = store.find(storedKey).orElseThrow();
        assertEquals(Status.COMPLETED, saved.getStatus());
        assertEquals(201, saved.getHttpStatus());
        assertEquals("order-1", saved.getBody());

        Duration left = store.getRemainingTtl(storedKey).orElseThrow();
        assertTrue(left.compareTo(Duration.ofMinutes(9)) > 0 && left.compareTo(Duration.ofMinutes(10)) <= 0,
                "@Idempotent(ttl = \"10m\") should apply, but was " + left);
    }

    // ---- refusals

    @Test
    void sameKeyWithADifferentBodyIsRefused() throws Exception {
        String key = newKey();
        send(order(key, JSON));
        MvcResult second = send(order(key, "{\"item\":\"pen\"}"));
        assertEquals(422, second.getResponse().getStatus());
        assertTrue(second.getResponse().getContentType().startsWith("application/problem+json"));
        assertEquals(1, api.orders.get());
    }

    @Test
    void sameKeyOnAnotherEndpointIsRefused() throws Exception {
        String key = newKey();
        send(order(key, JSON));
        MvcResult other = send(post("/empty").header(HEADER, key).contentType(MediaType.APPLICATION_JSON).content(JSON));
        assertEquals(422, other.getResponse().getStatus());
        assertEquals(0, api.empties.get());
    }

    @Test
    void missingKeyIsRefusedBeforeTheControllerRuns() throws Exception {
        MvcResult result = send(post("/orders").contentType(MediaType.APPLICATION_JSON).content(JSON));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals(0, api.orders.get());
    }

    @Test
    void blankKeyIsRefused() throws Exception {
        assertEquals(400, send(order("   ", JSON)).getResponse().getStatus());
        assertEquals(0, api.orders.get());
    }

    @Test
    void twoKeyHeadersAreRefused() throws Exception {
        MvcResult result = send(post("/orders").header(HEADER, newKey()).header(HEADER, newKey())
                .contentType(MediaType.APPLICATION_JSON).content(JSON));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals(0, api.orders.get());
    }

    @Test
    void aRequestBodyOverTheCapIsRefusedWith413() throws Exception {
        MvcResult result = send(order(newKey(), "x".repeat(3000)));
        assertEquals(413, result.getResponse().getStatus());
        assertEquals(0, api.orders.get());
    }

    @Test
    void anEndpointWithoutTheAnnotationIsNeverTouched() throws Exception {
        send(get("/plain"));
        send(get("/plain").header(HEADER, "same"));
        MvcResult third = send(get("/plain").header(HEADER, "same"));
        assertEquals(3, api.plain.get(), "no key needed, and nothing is replayed");
        assertNull(third.getResponse().getHeader("Idempotency-Replayed"));
    }

    // ---- failures are retryable

    @Test
    void aControllerThatThrowsGivesTheKeyBack() {
        String key = newKey();
        assertThrows(ServletException.class, () -> mvc.perform(post("/explode").header(HEADER, key)));
        assertThrows(ServletException.class, () -> mvc.perform(post("/explode").header(HEADER, key)));
        assertEquals(2, api.explosions.get(), "the second try must run the controller again");
        assertTrue(store.find(StoredKeys.build(null, key)).isEmpty());
    }

    @Test
    void aServerErrorResponseIsNotSavedSoTheRetryRunsAgain() throws Exception {
        String key = newKey();
        assertEquals(500, send(post("/broken").header(HEADER, key)).getResponse().getStatus());
        assertEquals(500, send(post("/broken").header(HEADER, key)).getResponse().getStatus());
        assertEquals(2, api.broken.get());
    }

    @Test
    void aClientErrorResponseIsSavedAndReplayed() throws Exception {
        String key = newKey();
        MvcResult first = send(post("/rejected").header(HEADER, key));
        MvcResult second = send(post("/rejected").header(HEADER, key));
        assertEquals(400, first.getResponse().getStatus());
        assertEquals(400, second.getResponse().getStatus());
        assertEquals("nope", second.getResponse().getContentAsString());
        assertEquals("true", second.getResponse().getHeader("Idempotency-Replayed"));
        assertEquals(1, api.rejected.get());
    }

    // ---- big responses

    @Test
    void aResponseOverTheLimitIsSentInFullButNotSaved() throws Exception {
        String key = newKey();
        MvcResult first = send(post("/big").header(HEADER, key));
        assertEquals(1000, first.getResponse().getContentAsString().length(), "the client must get everything");

        MvcResult second = send(post("/big").header(HEADER, key));
        assertEquals(1000, second.getResponse().getContentAsString().length());
        assertNull(second.getResponse().getHeader("Idempotency-Replayed"));
        assertEquals(2, api.big.get(), "it was not saved, so the retry runs again");
    }

    // ---- who is asking

    @Test
    void differentCallersCanUseTheSameKeyWithoutSharingAnswers() throws Exception {
        String key = newKey();
        MvcResult alice = send(order(key, JSON).principal(() -> "alice"));
        MvcResult bob = send(order(key, JSON).principal(() -> "bob"));
        assertEquals("order-1", alice.getResponse().getContentAsString());
        assertEquals("order-2", bob.getResponse().getContentAsString());

        MvcResult aliceAgain = send(order(key, JSON).principal(() -> "alice"));
        assertEquals("order-1", aliceAgain.getResponse().getContentAsString());
        assertEquals("true", aliceAgain.getResponse().getHeader("Idempotency-Replayed"));
        assertEquals(2, api.orders.get());
    }

    @Test
    void anonymousAndLoggedInCallersDoNotShareEither() throws Exception {
        String key = newKey();
        send(order(key, JSON));
        MvcResult loggedIn = send(order(key, JSON).principal(() -> "alice"));
        assertNull(loggedIn.getResponse().getHeader("Idempotency-Replayed"));
        assertEquals(2, api.orders.get());
    }

    // ---- two at once

    @Test
    void aSecondRequestWhileTheFirstIsStillRunningGets409WithRetryAfter() throws Exception {
        String key = newKey();
        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> {
            try {
                return send(post("/slow").header(HEADER, key));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(api.slowStarted.await(10, TimeUnit.SECONDS), "the first request never reached the controller");
        MvcResult second = send(post("/slow").header(HEADER, key));
        assertEquals(409, second.getResponse().getStatus());
        assertNotNull(second.getResponse().getHeader("Retry-After"));
        assertEquals(1, api.slow.get(), "the second request must not run the controller");

        api.slowRelease.countDown();
        assertEquals(200, first.get(10, TimeUnit.SECONDS).getResponse().getStatus());

        MvcResult third = send(post("/slow").header(HEADER, key));
        assertEquals(200, third.getResponse().getStatus());
        assertEquals("true", third.getResponse().getHeader("Idempotency-Replayed"));
        assertEquals(1, api.slow.get());
    }

    // ---------------------------------------------------------------------------------------------------------

    /** A tiny application to call. Added to the test application with @Import. */
    @RestController
    public static class Api {
        final AtomicInteger orders = new AtomicInteger();
        final AtomicInteger empties = new AtomicInteger();
        final AtomicInteger jsons = new AtomicInteger();
        final AtomicInteger plain = new AtomicInteger();
        final AtomicInteger explosions = new AtomicInteger();
        final AtomicInteger broken = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final AtomicInteger big = new AtomicInteger();
        final AtomicInteger slow = new AtomicInteger();
        volatile String lastBody;
        volatile CountDownLatch slowStarted = new CountDownLatch(1);
        volatile CountDownLatch slowRelease = new CountDownLatch(1);

        void reset() {
            for (AtomicInteger c : new AtomicInteger[]{orders, jsons, empties, plain, explosions, broken, rejected, big, slow}) {
                c.set(0);
            }
            lastBody = null;
            slowStarted = new CountDownLatch(1);
            slowRelease = new CountDownLatch(1);
        }

        @Idempotent(ttl = "10m")
        @PostMapping("/orders")
        public ResponseEntity<String> create(@RequestBody String body) {
            lastBody = body;
            return ResponseEntity.status(201).body("order-" + orders.incrementAndGet());
        }

        @Idempotent
        @PostMapping(value = "/unicode", produces = "text/plain;charset=UTF-8")
        public String unicode() {
            return "héllo ✓ 😀";
        }

        @Idempotent
        @PostMapping(value = "/json", produces = "application/json")
        public java.util.Map<String, Object> json() {
            return java.util.Map.of("id", jsons.incrementAndGet(), "name", "José");
        }

        @Idempotent
        @PostMapping("/empty")
        public ResponseEntity<Void> empty() {
            empties.incrementAndGet();
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/plain")
        public String plain() {
            plain.incrementAndGet();
            return "plain";
        }

        @Idempotent
        @PostMapping("/explode")
        public String explode() {
            explosions.incrementAndGet();
            throw new IllegalStateException("boom");
        }

        @Idempotent
        @PostMapping("/broken")
        public ResponseEntity<String> broken() {
            broken.incrementAndGet();
            return ResponseEntity.status(500).body("broken");
        }

        @Idempotent
        @PostMapping("/rejected")
        public ResponseEntity<String> rejected() {
            rejected.incrementAndGet();
            return ResponseEntity.badRequest().body("nope");
        }

        @Idempotent
        @PostMapping("/big")
        public String big() {
            big.incrementAndGet();
            return "x".repeat(1000);
        }

        @Idempotent
        @PostMapping("/slow")
        public String slow() throws InterruptedException {
            slow.incrementAndGet();
            slowStarted.countDown();
            slowRelease.await(10, TimeUnit.SECONDS);
            return "done";
        }
    }
}
