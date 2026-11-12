package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.annotation.Idempotent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F09: whatever a client can do to make a response unsaveable (a handler that echoes its input past the size limit, a
 * non-UTF-8 charset), the default policy turns the endpoint into an unprotected one: same key, handler runs twice.
 * With {@code on-unreplayable-response=block} the second request is refused instead. F10: 5xx can be replayed.
 */
@SpringBootTest(properties = {
        "idempotency.max-response-body-bytes=200",
        "idempotency.on-unreplayable-response=block",
        "idempotency.on-server-error=replay"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ResponsePolicyTests.Api.class})
class ResponsePolicyTests {

    @Autowired MockMvc mvc;
    @Autowired Api api;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void reset() {
        redis.execute((RedisCallback<Void>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
        api.runs.set(0);
    }

    private MvcResult send(String path, String key, String size) throws Exception {
        return mvc.perform(post(path).header("Idempotency-Key", key).param("size", size)).andReturn();
    }

    @Test
    void F09_aResponseTheClientMadeTooLargeToSaveDoesNotRunTheHandlerAgain() throws Exception {
        String key = UUID.randomUUID().toString();
        MvcResult first = send("/echo", key, "5000");
        MvcResult second = send("/echo", key, "5000");

        assertEquals(200, first.getResponse().getStatus());
        assertEquals(5000, first.getResponse().getContentAsString().length(), "the client still gets the whole response");
        assertEquals(409, second.getResponse().getStatus());
        assertEquals(1, api.runs.get(), "the handler must run once");
        assertEquals(true, second.getResponse().getContentAsString().contains("response-not-replayable"));
    }

    @Test
    void F09_aSmallResponseIsStillReplayedNormally() throws Exception {
        String key = UUID.randomUUID().toString();
        send("/echo", key, "10");
        MvcResult second = send("/echo", key, "10");

        assertEquals(200, second.getResponse().getStatus());
        assertEquals("true", second.getResponse().getHeader("Idempotency-Replayed"));
        assertEquals(1, api.runs.get());
    }

    @Test
    void F10_aServerErrorResponseCanBeReplayedInsteadOfRunningTheHandlerAgain() throws Exception {
        String key = UUID.randomUUID().toString();
        MvcResult first = send("/fails-after-side-effect", key, "0");
        MvcResult second = send("/fails-after-side-effect", key, "0");

        assertEquals(503, first.getResponse().getStatus());
        assertEquals(503, second.getResponse().getStatus());
        assertEquals("true", second.getResponse().getHeader("Idempotency-Replayed"));
        assertEquals(1, api.runs.get());
    }

    @RestController
    static class Api {
        final AtomicInteger runs = new AtomicInteger();

        @Idempotent
        @PostMapping("/echo")
        String echo(@RequestParam("size") int size) {
            runs.incrementAndGet();
            return "x".repeat(size);
        }

        @Idempotent
        @PostMapping("/fails-after-side-effect")
        ResponseEntity<String> fails() {
            runs.incrementAndGet();
            return ResponseEntity.status(503).body("charged, then the notification failed");
        }
    }
}
