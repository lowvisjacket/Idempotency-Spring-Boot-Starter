package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.annotation.Idempotent;
import io.github.lowvisjacket.idempotency.fingerprint.Fingerprint;
import io.github.lowvisjacket.idempotency.fingerprint.RequestWrapper;
import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;
import io.github.lowvisjacket.idempotency.model.Status;
import io.github.lowvisjacket.idempotency.store.IdempotencyStore;
import io.github.lowvisjacket.idempotency.store.RedisIdempotencyStore;
import io.github.lowvisjacket.idempotency.store.StoredKeys;
import io.github.lowvisjacket.idempotency.web.IdempotencyInterceptor;
import io.github.lowvisjacket.idempotency.web.ProblemResponseWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F16/F17: another tenant of a shared Redis can write whatever it likes under a key. Nothing read back may be sent to
 * a client unchecked, and the application's own ObjectMapper must not decide how records are written.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StoredRecordTrustTests {

    @RestController
    static class Api {
        @Idempotent
        @PostMapping("/pay")
        String pay() {
            return "ok";
        }
    }

    @Autowired StringRedisTemplate redis;

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/pay");
        request.addHeader("Idempotency-Key", "k1");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private MockHttpServletResponse replayOf(int status, String contentType) throws Exception {
        IdempotencyInterceptorTests.FakeStore store = new IdempotencyInterceptorTests.FakeStore();
        Fingerprint fingerprint = new Fingerprint();
        String fp = fingerprint.hash(new RequestWrapper(request()));
        IdempotencyRecord forged = new IdempotencyRecord(StoredKeys.build(null, "k1"), fp, contentType,
                Map.of("Location", List.of("/ok")), "<script>alert(1)</script>", status, Duration.ofMinutes(5));
        forged.setStatus(Status.COMPLETED);
        store.data.put(forged.getKey(), forged);
        IdempotencyInterceptor interceptor = new IdempotencyInterceptor(store, fingerprint,
                new ProblemResponseWriter(JsonMapper.builder().build()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(new RequestWrapper(request()), response,
                new HandlerMethod(new Api(), Api.class.getDeclaredMethod("pay")));
        return response;
    }

    @ParameterizedTest(name = "status {0} in a stored record is never sent")
    @ValueSource(ints = {-1, 0, 99, 100, 199, 600, 999, Integer.MAX_VALUE})
    void F16_aStoredRecordWithANonsenseStatusIsNotReplayed(int status) throws Exception {
        MockHttpServletResponse response = replayOf(status, "text/plain");

        assertEquals(503, response.getStatus());
        assertFalse(response.getContentAsString().contains("script"));
    }

    @ParameterizedTest(name = "content type #{index} with a line break in a stored record is never sent")
    @ValueSource(strings = {"text/plain\r\nSet-Cookie: session=evil", "text/plain\nX-Injected: 1", "text/plain\u0000"})
    void F16_aStoredContentTypeWithControlCharactersIsNotReplayed(String contentType) throws Exception {
        MockHttpServletResponse response = replayOf(200, contentType);

        assertEquals(503, response.getStatus());
        assertNull(response.getHeader("Set-Cookie"));
        assertNull(response.getHeader("X-Injected"));
    }

    @Test
    void F16_anOrdinaryRecordIsStillReplayed() throws Exception {
        MockHttpServletResponse response = replayOf(201, "application/json");

        assertEquals(201, response.getStatus());
        assertEquals("true", response.getHeader("Idempotency-Replayed"));
    }

    @Test
    void F17_theApplicationsDefaultTypingNeverLeaksIntoStoredRecords() {
        JsonMapper hostile = JsonMapper.builder()
                .activateDefaultTyping(BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class).build(),
                        DefaultTyping.NON_FINAL)
                .build();
        @SuppressWarnings("removal")
        IdempotencyStore store = new RedisIdempotencyStore(redis, hostile);
        String key = "idempotency:v1:anonymous:" + UUID.randomUUID();
        IdempotencyRecord record = new IdempotencyRecord(key, "fp", "text/plain", "body", 200, Duration.ofMinutes(1));

        assertTrue(store.claim(record));

        String raw = redis.opsForValue().get(key);
        assertNotNull(raw);
        assertFalse(raw.contains("@class"), "stored form depends on the application's mapper: " + raw);
        assertEquals("fp", store.find(key).orElseThrow().getFingerprint());
    }

    @Test
    void F17_aRecordWithFieldsThisVersionDoesNotKnowIsStillReadable() {
        String key = "idempotency:v1:anonymous:" + UUID.randomUUID();
        redis.opsForValue().set(key, "{\"fingerprint\":\"fp\",\"status\":\"PENDING\",\"fromTheFuture\":42}");
        IdempotencyStore store = new RedisIdempotencyStore(redis);

        assertEquals("fp", store.find(key).orElseThrow().getFingerprint());
    }
}
