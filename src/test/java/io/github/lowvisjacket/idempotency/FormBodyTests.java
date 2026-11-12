package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.annotation.Idempotent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * F03: form bodies under a real Tomcat. The request body is consumed by the library's filter, so Tomcat can no longer
 * parse form fields from it, and two different form posts fingerprint as the same empty body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, FormBodyTests.Api.class})
class FormBodyTests {

    @Value("${local.server.port}") int port;
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

    private HttpResponse<String> send(String method, String path, String key, String form) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method(method, HttpRequest.BodyPublishers.ofString(form));
        if (key != null) request.header("Idempotency-Key", key);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void F03_formFieldsAreStillBoundOnAnUnrelatedPostEndpoint() throws Exception {
        assertEquals("amount=5", send("POST", "/plain-form", null, "amount=5").body());
    }

    @Test
    void F03_formFieldsAreStillBoundOnAnIdempotentPostEndpoint() throws Exception {
        assertEquals("amount=5", send("POST", "/idem-form", UUID.randomUUID().toString(), "amount=5").body());
    }

    @Test
    void F03_twoDifferentFormPostsWithTheSameKeyAreNotTreatedAsTheSameRequest() throws Exception {
        String key = UUID.randomUUID().toString();
        send("POST", "/idem-form", key, "amount=5");
        HttpResponse<String> second = send("POST", "/idem-form", key, "amount=500");

        assertEquals(422, second.statusCode(), second.body());
        assertEquals(1, api.runs.get());
    }

    @Test
    void F03_twoDifferentFormPutsWithTheSameKeyAreNotTreatedAsTheSameRequest() throws Exception {
        String key = UUID.randomUUID().toString();
        send("PUT", "/idem-form", key, "amount=5");
        HttpResponse<String> second = send("PUT", "/idem-form", key, "amount=500");

        assertEquals(422, second.statusCode(), second.body());
        assertEquals(1, api.runs.get());
    }

    @Test
    void F03_theSameFormPostRepeatedIsReplayed() throws Exception {
        String key = UUID.randomUUID().toString();
        send("POST", "/idem-form", key, "amount=5&note=a");
        HttpResponse<String> second = send("POST", "/idem-form", key, "amount=5&note=a");

        assertEquals(200, second.statusCode());
        assertEquals("amount=5", second.body());
        assertEquals(1, api.runs.get());
    }

    @RestController
    static class Api {
        final AtomicInteger runs = new AtomicInteger();

        @PostMapping("/plain-form")
        String plain(@RequestParam("amount") String amount) {
            return "amount=" + amount;
        }

        @Idempotent
        @PostMapping("/idem-form")
        String idem(@RequestParam("amount") String amount) {
            runs.incrementAndGet();
            return "amount=" + amount;
        }

        @Idempotent
        @PutMapping("/idem-form")
        String idemPut(@RequestParam("amount") String amount) {
            runs.incrementAndGet();
            return "amount=" + amount;
        }
    }
}
