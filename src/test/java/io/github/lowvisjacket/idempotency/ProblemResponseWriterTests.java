package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.web.ProblemResponseWriter;
import io.github.lowvisjacket.idempotency.web.ProblemType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ProblemResponseWriter, written to try to break it.
 *
 * Rules under test:
 *  - Body is an RFC 9457 problem document: type, title, status (from ProblemType) and, only when given a non-blank
 *    detail, detail. Nothing else.
 *  - Content type is application/problem+json, UTF-8. Content-Length matches the body bytes exactly.
 *  - Retry-After is whole seconds, rounded UP, only when requested; zero or negative is an IllegalArgumentException.
 *  - Headers already on the response survive; a half-written body is discarded; a committed response is left alone.
 *  - The output is valid JSON whatever the detail contains.
 */
class ProblemResponseWriterTests {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final ProblemResponseWriter writer = new ProblemResponseWriter(mapper);

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(MockHttpServletResponse response) throws Exception {
        return mapper.readValue(response.getContentAsByteArray(), Map.class);
    }

    // --- The problem types themselves ---

    @ParameterizedTest(name = "{0} has status {1}")
    @CsvSource({
            "INVALID_KEY,          400",
            "REQUEST_IN_PROGRESS,  409",
            "BODY_TOO_LARGE,       413",
            "KEY_REUSED,           422",
            "STORE_UNAVAILABLE,    503"
    })
    void eachProblemTypeHasTheAgreedStatus(ProblemType problem, int expectedStatus) {
        assertEquals(expectedStatus, problem.status());
    }

    @Test
    void everyProblemTypeHasADistinctNonBlankTypeAndTitle() {
        Set<String> types = new HashSet<>();
        Set<String> titles = new HashSet<>();
        for (ProblemType problem : ProblemType.values()) {
            assertFalse(problem.type().isBlank(), problem + " type");
            assertFalse(problem.title().isBlank(), problem + " title");
            assertTrue(types.add(problem.type()), "duplicate type " + problem.type());
            assertTrue(titles.add(problem.title()), "duplicate title " + problem.title());
        }
    }

    @Test
    void typesAreStableUrnsInOurNamespace() {
        for (ProblemType problem : ProblemType.values()) {
            assertTrue(problem.type().matches("urn:idempotency:[a-z]+(-[a-z]+)*"),
                    problem + " has an unexpected type: " + problem.type());
        }
    }

    // --- Status, content type, encoding ---

    @ParameterizedTest
    @EnumSource(ProblemType.class)
    void theHttpStatusMatchesTheProblemType(ProblemType problem) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(writer.write(response, problem, "something"));

        assertEquals(problem.status(), response.getStatus());
    }

    @Test
    void theContentTypeIsProblemJsonNotPlainJson() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, ProblemType.INVALID_KEY, "x");

        assertTrue(response.getContentType().startsWith("application/problem+json"), response.getContentType());
        assertFalse(response.getContentType().startsWith("application/json"));
    }

    @Test
    void theResponseIsDeclaredAsUtf8() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, ProblemType.INVALID_KEY, "x");

        assertEquals("UTF-8", response.getCharacterEncoding());
    }

    // --- Body shape ---

    @ParameterizedTest
    @EnumSource(ProblemType.class)
    void theBodyHasExactlyTypeTitleStatusAndDetail(ProblemType problem) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, problem, "explanation");

        Map<String, Object> body = body(response);
        assertEquals(Set.of("type", "title", "status", "detail"), body.keySet());
        assertEquals(problem.type(), body.get("type"));
        assertEquals(problem.title(), body.get("title"));
        assertEquals(problem.status(), body.get("status"));
        assertEquals("explanation", body.get("detail"));
    }

    @Test
    void theStatusInTheBodyIsANumberNotAString() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, ProblemType.KEY_REUSED, "x");

        assertInstanceOf(Number.class, body(response).get("status"));
    }

    @ParameterizedTest(name = "detail \"{0}\" is left out")
    @ValueSource(strings = {"", " ", "   ", "\t", "\n", " \t\n "})
    void aBlankDetailIsOmittedFromTheBody(String detail) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, ProblemType.INVALID_KEY, detail);

        assertEquals(Set.of("type", "title", "status"), body(response).keySet());
    }

    @Test
    void aNullDetailIsOmittedFromTheBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, ProblemType.INVALID_KEY, null);

        assertEquals(Set.of("type", "title", "status"), body(response).keySet());
    }

    @Test
    void theThreeArgumentOverloadSendsNoRetryAfter() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, ProblemType.REQUEST_IN_PROGRESS, "busy");

        assertNull(response.getHeader("Retry-After"));
    }

    // --- Output must be valid JSON whatever the detail contains ---

    @ParameterizedTest(name = "detail #{index} survives a JSON round trip")
    @ValueSource(strings = {
            "plain",
            "with \"double quotes\"",
            "back\\slash",
            "new\nline and\ttab and\rcarriage return",
            "nul\0byte",
            "bell\u0007 and escape\u001b[31m",
            "café ключ 鍵",
            "emoji 🔑 key",
            "zero​width and line separator paragraph",
            "</script><script>alert(1)</script>",
            "{\"injected\":\"json\"}",
            "], \"status\": 200, \"x\": [",
            "unpaired surrogate \uD83D end"
    })
    void anyDetailProducesValidJsonThatRoundTrips(String detail) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, ProblemType.INVALID_KEY, detail);

        Map<String, Object> body = body(response);
        assertEquals(Set.of("type", "title", "status", "detail"), body.keySet(), "injected members must not appear");
        assertEquals(400, body.get("status"), "injected status must not win");
        String roundTripped = (String) body.get("detail");
        // An unpaired surrogate cannot be encoded in UTF-8, so Jackson may replace it; everything else is exact.
        if (!detail.contains("\uD83D end")) {
            assertEquals(detail, roundTripped);
        } else {
            assertTrue(roundTripped.startsWith("unpaired surrogate"));
        }
    }

    @Test
    void aVeryLongDetailStillProducesValidJson() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String detail = "x".repeat(2_000_000);

        writer.write(response, ProblemType.INVALID_KEY, detail);

        assertEquals(detail, body(response).get("detail"));
    }

    @Test
    void theBodyIsEncodedAsUtf8Bytes() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, ProblemType.INVALID_KEY, "café 🔑");

        String decoded = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(decoded.contains("café 🔑") || decoded.contains("caf\\u00e9"),
                "detail must be recoverable from the UTF-8 bytes: " + decoded);
        assertEquals("café 🔑", body(response).get("detail"));
    }

    @Test
    void contentLengthMatchesTheBodyBytesExactlyEvenWithMultiByteText() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, ProblemType.INVALID_KEY, "café 鍵 🔑");

        assertEquals(response.getContentAsByteArray().length, response.getContentLength());
        assertTrue(response.getContentLength() > 0);
    }

    @Test
    void theBodyIsASingleJsonDocumentWithNothingAfterIt() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, ProblemType.INVALID_KEY, "x");

        String text = response.getContentAsString(StandardCharsets.UTF_8).trim();
        assertTrue(text.startsWith("{") && text.endsWith("}"), text);
        assertEquals(1, text.chars().filter(c -> c == '{').count(), "no nested or repeated objects: " + text);
    }

    // --- Retry-After ---

    @ParameterizedTest(name = "{0} gives Retry-After: {1}")
    @CsvSource({
            "PT1S,        1",
            "PT30S,       30",
            "PT1M,        60",
            "PT1H,        3600",
            "P1D,         86400",
            "PT1.5S,      2",    // rounds UP, never down
            "PT0.001S,    1",    // a single millisecond still waits one second
            "PT29.1S,     30",
            "PT59.999S,   60",
            "P3650D,      315360000"
    })
    void retryAfterIsWholeSecondsRoundedUp(Duration retryAfter, String expectedHeader) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, ProblemType.STORE_UNAVAILABLE, "down", retryAfter);

        assertEquals(expectedHeader, response.getHeader("Retry-After"));
        assertTrue(response.getHeader("Retry-After").matches("[1-9][0-9]*"), "must be plain digits");
    }

    @Test
    void aNullRetryAfterSendsNoHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, ProblemType.STORE_UNAVAILABLE, "down", null);

        assertNull(response.getHeader("Retry-After"));
    }

    @ParameterizedTest(name = "retryAfter {0} is rejected")
    @ValueSource(strings = {"PT0S", "PT-1S", "PT-0.001S", "-P1D"})
    void aZeroOrNegativeRetryAfterIsRejected(Duration retryAfter) {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(IllegalArgumentException.class,
                () -> writer.write(response, ProblemType.STORE_UNAVAILABLE, "down", retryAfter));
    }

    @Test
    void aRejectedRetryAfterLeavesTheResponseUntouched() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(IllegalArgumentException.class,
                () -> writer.write(response, ProblemType.STORE_UNAVAILABLE, "down", Duration.ZERO));

        assertEquals(200, response.getStatus());
        assertEquals(0, response.getContentAsByteArray().length);
    }

    // --- The state of the response it is given ---

    @Test
    void headersAlreadyOnTheResponseAreKept() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("Access-Control-Allow-Origin", "https://example.com");
        response.setHeader("X-Request-Id", "abc-123");

        writer.write(response, ProblemType.INVALID_KEY, "x");

        assertEquals("https://example.com", response.getHeader("Access-Control-Allow-Origin"));
        assertEquals("abc-123", response.getHeader("X-Request-Id"));
    }

    @Test
    void aStaleContentTypeOrStatusFromBeforeIsReplaced() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        response.setContentType("text/html");

        writer.write(response, ProblemType.KEY_REUSED, "x");

        assertEquals(422, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/problem+json"));
    }

    @Test
    void aBodyBufferedButNotYetSentIsDiscarded() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getOutputStream().write("<html>partial page</html>".getBytes(StandardCharsets.UTF_8));

        writer.write(response, ProblemType.INVALID_KEY, "x");

        String text = response.getContentAsString(StandardCharsets.UTF_8);
        assertFalse(text.contains("partial page"), text);
        assertEquals(400, body(response).get("status"));
    }

    @Test
    void aCommittedResponseIsLeftAloneAndFalseIsReturned() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        response.setCommitted(true);

        boolean written = writer.write(response, ProblemType.INVALID_KEY, "x");

        assertFalse(written);
        assertEquals(200, response.getStatus());
        assertEquals(0, response.getContentAsByteArray().length);
        assertNull(response.getHeader("Retry-After"));
    }

    @Test
    void writingTwiceLeavesOnlyTheSecondProblem() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, ProblemType.INVALID_KEY, "first");
        writer.write(response, ProblemType.STORE_UNAVAILABLE, "second", Duration.ofSeconds(5));

        assertEquals(503, response.getStatus());
        Map<String, Object> body = body(response);
        assertEquals("second", body.get("detail"));
        assertEquals(503, body.get("status"));
        assertEquals("5", response.getHeader("Retry-After"));
    }

    // --- Bad arguments ---

    @Test
    void aNullResponseIsRejected() {
        assertThrows(NullPointerException.class, () -> writer.write(null, ProblemType.INVALID_KEY, "x"));
    }

    @Test
    void aNullProblemTypeIsRejectedAndTheResponseIsUntouched() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(NullPointerException.class, () -> writer.write(response, null, "x"));

        assertEquals(200, response.getStatus());
    }

    @Test
    void aNullObjectMapperIsRejectedAtConstruction() {
        assertThrows(NullPointerException.class, () -> new ProblemResponseWriter(null));
    }

    // --- Sharing one instance ---

    @Test
    void oneWriterCanServeManyThreadsAtOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int id = t;
                results.add(pool.submit((Callable<Boolean>) () -> {
                    for (int i = 0; i < 200; i++) {
                        MockHttpServletResponse response = new MockHttpServletResponse();
                        String detail = "thread-" + id + "-call-" + i;
                        writer.write(response, ProblemType.REQUEST_IN_PROGRESS, detail, Duration.ofSeconds(id + 1));
                        Map<String, Object> body = body(response);
                        if (!detail.equals(body.get("detail"))) return false;
                        if (!String.valueOf(id + 1).equals(response.getHeader("Retry-After"))) return false;
                        if (response.getStatus() != 409) return false;
                    }
                    return true;
                }));
            }
            for (Future<Boolean> result : results) {
                assertTrue(result.get(), "a thread saw another thread's data");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
