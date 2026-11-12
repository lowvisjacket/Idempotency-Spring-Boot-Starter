package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.exception.FingerprintException;
import io.github.lowvisjacket.idempotency.fingerprint.Fingerprint;
import io.github.lowvisjacket.idempotency.fingerprint.RequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain unit tests (no Spring, no Redis) for Fingerprint.hash.
 *
 * Requests are built in request(...) and wrapped in RequestWrapper (as the filter will do)
 * before hashing, in hash(...).
 */
class FingerprintHashTests {

    private final Fingerprint fingerprint = new Fingerprint();

    private HttpServletRequest request(String method, String uri, String body, String... headerNameValuePairs) {
        URI parsed = URI.create(uri);
        MockHttpServletRequest request = new MockHttpServletRequest(method, parsed.getRawPath());
        request.setQueryString(parsed.getRawQuery());
        request.setCharacterEncoding("UTF-8");
        if (body != null) {
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        for (int i = 0; i < headerNameValuePairs.length; i += 2) {
            request.addHeader(headerNameValuePairs[i], headerNameValuePairs[i + 1]);
        }
        return request;
    }

    private String hash(HttpServletRequest request) throws Exception {
        return fingerprint.hash(new RequestWrapper(request));
    }

    private HttpServletRequest baseline() {
        return request("POST", "http://localhost/payments?currency=usd", "{\"amount\":50}");
    }

    @Test
    void sameRequestGivesSameHash() throws Exception {
        assertEquals(hash(baseline()), hash(baseline()));
    }

    @Test
    void hashIs64LowercaseHexCharacters() throws Exception {
        assertTrue(hash(baseline()).matches("[0-9a-f]{64}"));
    }

    @Test
    void earlierCallsDoNotAffectLaterResults() throws Exception {
        String first = hash(baseline());

        hash(request("DELETE", "http://localhost/other", "something else entirely"));

        assertEquals(first, hash(baseline()));
    }

    @Test
    void differentMethodGivesDifferentHash() throws Exception {
        HttpServletRequest put = request("PUT", "http://localhost/payments?currency=usd", "{\"amount\":50}");

        assertNotEquals(hash(baseline()), hash(put));
    }

    @Test
    void differentPathGivesDifferentHash() throws Exception {
        HttpServletRequest other = request("POST", "http://localhost/refunds?currency=usd", "{\"amount\":50}");

        assertNotEquals(hash(baseline()), hash(other));
    }

    @Test
    void differentQueryGivesDifferentHash() throws Exception {
        HttpServletRequest other = request("POST", "http://localhost/payments?currency=eur", "{\"amount\":50}");

        assertNotEquals(hash(baseline()), hash(other));
    }

    @Test
    void differentBodyGivesDifferentHash() throws Exception {
        HttpServletRequest other = request("POST", "http://localhost/payments?currency=usd", "{\"amount\":51}");

        assertNotEquals(hash(baseline()), hash(other));
    }

    @Test
    void emptyBodyDiffersFromNonEmptyBody() throws Exception {
        HttpServletRequest none = request("POST", "http://localhost/payments?currency=usd", null);

        assertNotEquals(hash(baseline()), hash(none));
    }

    @Test
    void idempotencyKeyHeaderDoesNotAffectHash() throws Exception {
        HttpServletRequest first = request("POST", "http://localhost/payments?currency=usd", "{\"amount\":50}",
                "Idempotency-Key", "key-1");
        HttpServletRequest second = request("POST", "http://localhost/payments?currency=usd", "{\"amount\":50}",
                "Idempotency-Key", "key-2");

        assertEquals(hash(first), hash(second));
    }

    @Test
    void perAttemptHeadersDoNotAffectHash() throws Exception {
        HttpServletRequest first = request("POST", "http://localhost/payments?currency=usd", "{\"amount\":50}",
                "X-Request-Id", "attempt-1");
        HttpServletRequest retry = request("POST", "http://localhost/payments?currency=usd", "{\"amount\":50}",
                "X-Request-Id", "attempt-2");

        assertEquals(hash(first), hash(retry));
    }

    @Test
    void unicodeFormsOfTheSameTextGiveTheSameHash() throws Exception {
        String composed = "{\"name\":\"café\"}";
        String decomposed = "{\"name\":\"café\"}";

        assertEquals(
                hash(request("POST", "http://localhost/people", composed)),
                hash(request("POST", "http://localhost/people", decomposed)));
    }

    @Test
    void noQueryIsNotTheSameAsAQueryThatSpellsNull() throws Exception {
        HttpServletRequest noQuery = request("GET", "http://localhost/search", null);
        HttpServletRequest nullQuery = request("GET", "http://localhost/search?null", null);

        assertNotEquals(hash(noQuery), hash(nullQuery));
    }

    @Test
    void partsAreNotJustGluedTogether() throws Exception {
        HttpServletRequest a = request("POST", "http://localhost/ab?c=1", "x");
        HttpServletRequest b = request("POST", "http://localhost/a?bc=1", "x");
        HttpServletRequest c = request("POST", "http://localhost/a", "b?c=1x");

        assertNotEquals(hash(a), hash(b));
        assertNotEquals(hash(a), hash(c));
    }

    @Test
    void delimiterInsideOnePartCannotForgeAnotherRequest() throws Exception {
        MockHttpServletRequest a = new MockHttpServletRequest("POST", "/p");
        a.setQueryString("c");
        a.setContent("a|b".getBytes(StandardCharsets.UTF_8));

        MockHttpServletRequest b = new MockHttpServletRequest("POST", "/p");
        b.setQueryString("b|c");
        b.setContent("a".getBytes(StandardCharsets.UTF_8));

        assertNotEquals(hash(a), hash(b));
    }

    @Test
    void bodyLineEndingsAndTrailingNewlinesAreSignificant() throws Exception {
        String lf = hash(request("POST", "http://localhost/p", "line1\nline2"));
        String crlf = hash(request("POST", "http://localhost/p", "line1\r\nline2"));
        String trailing = hash(request("POST", "http://localhost/p", "line1\nline2\n"));

        assertNotEquals(lf, crlf);
        assertNotEquals(lf, trailing);
    }

    @Test
    void bodyIsStillReadableAfterHashing() throws Exception {
        RequestWrapper wrapper = new RequestWrapper(request("POST", "http://localhost/p", "{\"amount\":50}"));

        fingerprint.hash(wrapper);

        assertEquals("{\"amount\":50}", new String(wrapper.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals("{\"amount\":50}", wrapper.getReader().readLine());
        assertEquals("{\"amount\":50}", new String(wrapper.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                "the body can be read any number of times");
    }

    @Test
    void differentBinaryBodiesDoNotCollide() throws Exception {
        MockHttpServletRequest a = new MockHttpServletRequest("POST", "/upload");
        a.setContent(new byte[]{(byte) 0xff, (byte) 0xfe, 0x01});
        MockHttpServletRequest b = new MockHttpServletRequest("POST", "/upload");
        b.setContent(new byte[]{(byte) 0xff, (byte) 0xfe, 0x02});

        assertNotEquals(hash(a), hash(b));
    }

    @Test
    void readerUsesTheDeclaredCharset() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/p");
        request.setCharacterEncoding("ISO-8859-1");
        request.setContent("café".getBytes(StandardCharsets.ISO_8859_1));

        assertEquals("café", new RequestWrapper(request).getReader().readLine());
    }

    @Test
    void bodyExactlyAtTheLimitIsAccepted() throws Exception {
        String result = hash(request("POST", "http://localhost/upload", "x".repeat(IdempotencyDefaults.MAX_BODY_SIZE_BYTES)));

        assertEquals(64, result.length());
        assertNotEquals(hash(baseline()), result);
    }

    @Test
    void bodyOneByteOverTheLimitIsRejected() {
        assertThrows(FingerprintException.class,
                () -> hash(request("POST", "http://localhost/upload", "x".repeat(IdempotencyDefaults.MAX_BODY_SIZE_BYTES + 1))));
    }

    @Test
    void hugeBodyIsRejected() {
        assertThrows(FingerprintException.class,
                () -> hash(request("POST", "http://localhost/upload", "x".repeat(5_000_000))));
    }

    @Test
    void concurrentCallsAllGetTheSameResult() throws Exception {
        String expected = hash(baseline());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                results.add(pool.submit((Callable<Boolean>) () -> {
                    for (int i = 0; i < 500; i++) {
                        if (!expected.equals(hash(baseline()))) return false;
                    }
                    return true;
                }));
            }
            for (Future<Boolean> result : results) {
                assertTrue(result.get(), "a thread saw a different hash for the same request");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
