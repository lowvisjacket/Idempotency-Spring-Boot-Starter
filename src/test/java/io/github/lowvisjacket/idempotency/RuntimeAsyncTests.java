package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.annotation.Idempotent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * F08: the startup check looks at the declared return type, so a handler declared as {@code Object} that returns a
 * DeferredResult passes it. Under a real container the async dispatch must neither be refused by the library nor leave
 * the key locked for the whole TTL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, RuntimeAsyncTests.Api.class})
class RuntimeAsyncTests {

    @Value("${local.server.port}") int port;

    private HttpResponse<String> post(String key) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/deferred"))
                .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.noBody()).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void F08_aDeferredResultBehindAnObjectReturnTypeStillAnswersTheClient() throws Exception {
        HttpResponse<String> response = post(UUID.randomUUID().toString());

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("deferred-1", response.body());
    }

    @Test
    void F08_aRetryIsNotLockedOutForTheWholeTtl() throws Exception {
        String key = UUID.randomUUID().toString();
        post(key);

        assertNotEquals(409, post(key).statusCode());
    }

    @RestController
    static class Api {
        final AtomicInteger runs = new AtomicInteger();

        @Idempotent
        @PostMapping("/deferred")
        Object deferred() {
            DeferredResult<String> result = new DeferredResult<>();
            int run = runs.incrementAndGet();
            new Thread(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
                result.setResult("deferred-" + run);
            }).start();
            return result;
        }
    }
}
