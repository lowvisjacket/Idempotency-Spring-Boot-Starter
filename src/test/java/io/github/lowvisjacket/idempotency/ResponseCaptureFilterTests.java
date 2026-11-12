package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.exception.RedisException;
import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;
import io.github.lowvisjacket.idempotency.model.Status;
import io.github.lowvisjacket.idempotency.web.CapturingResponse;
import io.github.lowvisjacket.idempotency.web.ResponseCaptureFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ResponseCaptureFilter}, written before the logic.
 *
 * <p>Each test plays both roles around the filter: the "controller" is a lambda in the filter chain, and what the
 * interceptor does when it wins a claim (set the claim attribute, call {@code startCapturing()}) is done by the
 * helper {@link #interceptorWins}. The store is the in-memory fake from {@link IdempotencyInterceptorTests}.
 *
 * <p>Rules these tests pin down:
 * <ul>
 *   <li>A request that claimed nothing is never touched: no store calls, whatever the status.</li>
 *   <li>Status 500 and above, an over-limit body, a body that is not valid UTF-8, and any controller failure
 *       delete the claim. Everything else (2xx, 3xx, 4xx) completes it with the real status and body.</li>
 *   <li>The record is final <em>before</em> the client can see the response.</li>
 *   <li>The client always gets the response in full, whatever happens in the store; a store failure never turns a
 *       finished request into an error, and never hides the controller's own exception.</li>
 * </ul>
 */
class ResponseCaptureFilterTests {

    private static final String KEY = "idempotency:v1:anonymous:k1";

    private final IdempotencyInterceptorTests.FakeStore store = new IdempotencyInterceptorTests.FakeStore();
    private final ResponseCaptureFilter filter = new ResponseCaptureFilter(store);
    private final IdempotencyRecord claim = new IdempotencyRecord(KEY, "fp-123", null, null, 0, Duration.ofSeconds(90));

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** What the interceptor leaves behind when it wins a claim. */
    private static void interceptorWins(ServletRequest request, ServletResponse response, IdempotencyRecord claim) {
        request.setAttribute(IdempotencyDefaults.CLAIM_ATTRIBUTE, claim);
        CapturingResponse capture = WebUtils.getNativeResponse(response, CapturingResponse.class);
        assertNotNull(capture, "the filter must hand a CapturingResponse down the chain");
        capture.startCapturing();
    }

    /** The record the interceptor claimed is in the store as PENDING. */
    private void seed() {
        assertTrue(store.claim(claim));
        store.resetCounters();
    }

    private MockHttpServletResponse run(FilterChain chain) throws Exception {
        MockHttpServletResponse real = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/orders"), real, chain);
        return real;
    }

    /** A claimed request whose controller answers with the given status and body (null: no body). */
    private MockHttpServletResponse serve(int status, String body) throws Exception {
        seed();
        return run((request, response) -> {
            interceptorWins(request, response, claim);
            ((HttpServletResponse) response).setStatus(status);
            if (body != null) response.getOutputStream().write(utf8(body));
        });
    }

    private IdempotencyRecord stored() {
        return store.data.get(KEY);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    @ParameterizedTest(name = "status {0} with no claim")
    @ValueSource(ints = {200, 404, 500})
    void aRequestThatClaimedNothingIsNeverTouched(int status) throws Exception {
        MockHttpServletResponse real = run((request, response) -> {
            ((HttpServletResponse) response).setStatus(status);
            response.getOutputStream().write(utf8("plain answer"));
        });

        assertEquals(status, real.getStatus());
        assertEquals("plain answer", real.getContentAsString());
        assertEquals(0, store.totalCalls());
    }

    @Test
    void theChainReceivesACapturingResponse() throws Exception {
        List<ServletResponse> seen = new ArrayList<>();

        run((request, response) -> seen.add(response));

        assertInstanceOf(CapturingResponse.class, seen.get(0));
    }

    @Test
    void aClaimAttributeOfTheWrongTypeIsIgnored() throws Exception {
        MockHttpServletResponse real = run((request, response) -> {
            request.setAttribute(IdempotencyDefaults.CLAIM_ATTRIBUTE, "not a record");
            response.getOutputStream().write(utf8("fine"));
        });

        assertEquals("fine", real.getContentAsString());
        assertEquals(0, store.totalCalls());
    }

    @Test
    void aFailureWithNoClaimIsRethrownWithoutStoreCalls() {
        IllegalStateException failure = new IllegalStateException("boom");

        Throwable thrown = assertThrows(Throwable.class, () -> run((request, response) -> sneakyThrow(failure)));

        assertSame(failure, thrown);
        assertEquals(0, store.totalCalls());
    }

    @Test
    void aFinishedRequestIsSavedWithItsStatusBodyFingerprintAndTtl() throws Exception {
        MockHttpServletResponse real = serve(201, "{\"id\":7}");

        IdempotencyRecord saved = stored();
        assertEquals(Status.COMPLETED, saved.getStatus());
        assertEquals(201, saved.getHttpStatus());
        assertEquals("{\"id\":7}", saved.getBody());
        assertEquals("fp-123", saved.getFingerprint());
        assertEquals(KEY, saved.getKey());
        assertEquals(1, store.completes.get());
        assertEquals(0, store.deletes.get());
        assertEquals(201, real.getStatus());
        assertEquals("{\"id\":7}", real.getContentAsString());
    }

    @Test
    void completionResetsTheTtlToTheClaimsTtl() throws Exception {
        List<IdempotencyRecord> completed = new ArrayList<>();
        store.onComplete = completed::add;

        serve(200, "ok");

        assertEquals(Duration.ofSeconds(90), completed.get(0).getTtl());
    }

    @ParameterizedTest(name = "status {0} is saved for replay")
    @ValueSource(ints = {200, 201, 202, 204, 301, 302, 304, 400, 401, 403, 404, 409, 418, 422, 429, 499})
    void everyStatusBelow500IsSaved(int status) throws Exception {
        MockHttpServletResponse real = serve(status, "body-" + status);

        assertEquals(Status.COMPLETED, stored().getStatus());
        assertEquals(status, stored().getHttpStatus());
        assertEquals("body-" + status, stored().getBody());
        assertEquals(0, store.deletes.get());
        assertEquals(status, real.getStatus());
        assertEquals("body-" + status, real.getContentAsString());
    }

    @Test
    void anEmptyBodyIsSavedAsNull() throws Exception {
        MockHttpServletResponse real = serve(204, null);

        assertEquals(Status.COMPLETED, stored().getStatus());
        assertNull(stored().getBody());
        assertEquals(204, real.getStatus());
        assertEquals(0, real.getContentAsByteArray().length);
    }

    @Test
    void theClaimedRecordObjectIsNotChanged() throws Exception {
        serve(201, "{\"id\":7}");

        assertEquals(Status.PENDING, claim.getStatus());
        assertNull(claim.getBody());
        assertEquals(0, claim.getHttpStatus());
    }

    @Test
    void textWrittenThroughTheStreamKeepsItsUnicode() throws Exception {
        String text = "José ✓ 日本語 😀";

        MockHttpServletResponse real = serve(200, text);

        assertEquals(text, stored().getBody());
        assertArrayEquals(utf8(text), real.getContentAsByteArray());
    }

    @Test
    void textWrittenThroughTheWriterIsSavedAndSent() throws Exception {
        seed();
        String text = "José ✓ 日本語";

        MockHttpServletResponse real = run((request, response) -> {
            interceptorWins(request, response, claim);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(text);
        });

        assertEquals(text, stored().getBody());
        assertArrayEquals(utf8(text), real.getContentAsByteArray());
    }

    @Test
    void sendErrorWithAClientErrorIsSavedWithAnEmptyBody() throws Exception {
        seed();

        MockHttpServletResponse real = run((request, response) -> {
            interceptorWins(request, response, claim);
            ((HttpServletResponse) response).sendError(404);
        });

        assertEquals(Status.COMPLETED, stored().getStatus());
        assertEquals(404, stored().getHttpStatus());
        assertNull(stored().getBody());
        assertEquals(404, real.getStatus());
    }

    @Test
    void headersSetByTheControllerStillReachTheClient() throws Exception {
        seed();

        MockHttpServletResponse real = run((request, response) -> {
            interceptorWins(request, response, claim);
            HttpServletResponse http = (HttpServletResponse) response;
            http.setContentType("application/json");
            http.setHeader("Location", "/orders/7");
            http.addHeader("X-Many", "a");
            http.addHeader("X-Many", "b");
            response.getOutputStream().write(utf8("{}"));
        });

        assertEquals("application/json", real.getContentType());
        assertEquals("/orders/7", real.getHeader("Location"));
        assertEquals(List.of("a", "b"), real.getHeaders("X-Many"));
    }

    /** A claimed request whose controller sets the given Content-Type and writes the given bytes. */
    private MockHttpServletResponse serveTyped(String contentType, byte[] body) throws Exception {
        seed();
        return run((request, response) -> {
            interceptorWins(request, response, claim);
            if (contentType != null) response.setContentType(contentType);
            response.getOutputStream().write(body);
        });
    }

    @Test
    void theContentTypeTheControllerSetIsSaved() throws Exception {
        serveTyped("application/json", utf8("{}"));

        assertEquals("application/json", stored().getContentType());
        assertEquals(Status.COMPLETED, stored().getStatus());
    }

    @Test
    void theSavedContentTypeKeepsItsCharset() throws Exception {
        MockHttpServletResponse real = serveTyped("text/plain;charset=UTF-8", utf8("héllo"));

        assertNotNull(stored().getContentType());
        assertEquals(real.getContentType(), stored().getContentType(), "saved exactly as the client received it");
        assertTrue(stored().getContentType().toLowerCase().contains("utf-8"));
    }

    @Test
    void aResponseWithNoContentTypeSavesNull() throws Exception {
        seed();
        run((request, response) -> {
            interceptorWins(request, response, claim);
            ((HttpServletResponse) response).setStatus(204);
        });

        assertNull(stored().getContentType());
        assertEquals(Status.COMPLETED, stored().getStatus());
    }

    @Test
    void aContentTypeSetAfterTheBodyWasWrittenIsStillSaved() throws Exception {
        seed();
        run((request, response) -> {
            interceptorWins(request, response, claim);
            response.getOutputStream().write(utf8("{}"));
            response.setContentType("application/json");
        });

        assertEquals("application/json", stored().getContentType());
    }

    @Test
    void theLastContentTypeSetWins() throws Exception {
        seed();
        run((request, response) -> {
            interceptorWins(request, response, claim);
            response.setContentType("text/html");
            response.setContentType("application/json");
            response.getOutputStream().write(utf8("{}"));
        });

        assertEquals("application/json", stored().getContentType());
    }

    @Test
    void aContentTypeSetBeforeAResetIsNotSaved() throws Exception {
        seed();
        run((request, response) -> {
            interceptorWins(request, response, claim);
            response.setContentType("text/html");
            ((HttpServletResponse) response).reset();
            response.getOutputStream().write(utf8("plain"));
        });

        assertNull(stored().getContentType());
    }

    @Test
    void theContentTypeIsStillSentToTheClient() throws Exception {
        MockHttpServletResponse real = serveTyped("application/json", utf8("{}"));

        assertEquals("application/json", real.getContentType());
    }

    @Test
    void aClaimThatIsDeletedSavesNoContentType() throws Exception {
        seed();
        run((request, response) -> {
            interceptorWins(request, response, claim);
            response.setContentType("application/json");
            ((HttpServletResponse) response).setStatus(500);
            response.getOutputStream().write(utf8("{}"));
        });

        assertNull(stored(), "a server error is deleted, not saved");
        assertEquals(1, store.deletes.get());
    }

    @ParameterizedTest(name = "{0} with non-ASCII bytes is not saved")
    @ValueSource(strings = {"text/plain;charset=ISO-8859-1", "text/plain; charset=windows-1252",
            "application/json;charset=UTF-16", "text/plain;charset=no-such-charset"})
    void aNonUtf8CharsetWithNonAsciiTextIsNotSavedButStillSent(String contentType) throws Exception {
        byte[] bytes = utf8("café");

        MockHttpServletResponse real = serveTyped(contentType, bytes);

        assertNull(stored(), "the claim must be deleted so a retry can run again");
        assertEquals(1, store.deletes.get());
        assertEquals(0, store.completes.get());
        assertArrayEquals(bytes, real.getContentAsByteArray(), "the client still gets the response");
    }

    @ParameterizedTest(name = "{0} with ASCII only is saved")
    @ValueSource(strings = {"text/plain;charset=ISO-8859-1", "application/json;charset=UTF-16",
            "text/plain;charset=no-such-charset"})
    void aNonUtf8CharsetWithAsciiOnlyTextIsSaved(String contentType) throws Exception {
        serveTyped(contentType, utf8("plain ascii"));

        assertEquals(Status.COMPLETED, stored().getStatus());
        assertEquals(contentType, stored().getContentType());
    }

    @ParameterizedTest(name = "{0} with non-ASCII is saved")
    @ValueSource(strings = {"text/plain;charset=UTF-8", "text/plain;charset=utf-8", "text/plain; charset=\"UTF-8\"",
            "application/json", "text/plain"})
    void utf8OrNoCharsetWithNonAsciiTextIsSaved(String contentType) throws Exception {
        serveTyped(contentType, utf8("café ✓"));

        assertEquals(Status.COMPLETED, stored().getStatus());
        assertEquals("café ✓", stored().getBody());
    }

    @Test
    void aGarbageContentTypeWithAsciiOnlyTextDoesNotBreakAnything() throws Exception {
        MockHttpServletResponse real = serveTyped("not a media type;;;", utf8("ok"));

        assertEquals("ok", real.getContentAsString());
        assertEquals(Status.COMPLETED, stored().getStatus());
    }

    @Test
    void aGarbageContentTypeWithNonAsciiTextIsStillSentWhateverIsSaved() throws Exception {
        byte[] bytes = utf8("café");

        MockHttpServletResponse real = serveTyped("not a media type;;;", bytes);

        assertArrayEquals(bytes, real.getContentAsByteArray());
        assertEquals(1, store.completes.get() + store.deletes.get(), "the claim must be settled one way or the other");
    }

    @Test
    void aGetRequestIsHandledToo() throws Exception {
        seed();
        MockHttpServletResponse real = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/orders/7"), real, (request, response) -> {
            interceptorWins(request, response, claim);
            response.getOutputStream().write(utf8("order 7"));
        });

        assertEquals("order 7", stored().getBody());
        assertEquals("order 7", real.getContentAsString());
    }

    @ParameterizedTest(name = "status {0} deletes the claim")
    @ValueSource(ints = {500, 501, 502, 503, 504, 599})
    void aServerErrorDeletesTheClaimSoTheClientCanRetry(int status) throws Exception {
        MockHttpServletResponse real = serve(status, "server said no");

        assertFalse(store.data.containsKey(KEY));
        assertEquals(1, store.deletes.get());
        assertEquals(0, store.completes.get());
        assertEquals(status, real.getStatus(), "the client still sees the real error");
        assertEquals("server said no", real.getContentAsString());
    }

    @Test
    void sendErrorWithAServerErrorDeletesTheClaim() throws Exception {
        seed();

        MockHttpServletResponse real = run((request, response) -> {
            interceptorWins(request, response, claim);
            ((HttpServletResponse) response).sendError(500);
        });

        assertFalse(store.data.containsKey(KEY));
        assertEquals(500, real.getStatus());
    }

    @Test
    void aBodyThatIsNotValidUtf8CannotBeSavedSoTheClaimIsDeleted() throws Exception {
        seed();
        byte[] binary = {(byte) 0xFF, (byte) 0xFE, 0x00, (byte) 0x80};

        MockHttpServletResponse real = run((request, response) -> {
            interceptorWins(request, response, claim);
            response.getOutputStream().write(binary);
        });

        assertFalse(store.data.containsKey(KEY));
        assertEquals(0, store.completes.get());
        assertArrayEquals(binary, real.getContentAsByteArray(), "the client still gets the exact bytes");
    }

    @Test
    void aBodyOverTheLimitIsSentInFullButTheClaimIsDeleted() throws Exception {
        ResponseCaptureFilter small = new ResponseCaptureFilter(store, 8);
        seed();
        MockHttpServletResponse real = new MockHttpServletResponse();

        small.doFilter(new MockHttpServletRequest("POST", "/orders"), real, (request, response) -> {
            interceptorWins(request, response, claim);
            response.getOutputStream().write(utf8("123456789"));
        });

        assertFalse(store.data.containsKey(KEY));
        assertEquals(0, store.completes.get());
        assertEquals("123456789", real.getContentAsString());
    }

    @Test
    void aBodyExactlyAtTheLimitIsStillSaved() throws Exception {
        ResponseCaptureFilter small = new ResponseCaptureFilter(store, 8);
        seed();
        MockHttpServletResponse real = new MockHttpServletResponse();

        small.doFilter(new MockHttpServletRequest("POST", "/orders"), real, (request, response) -> {
            interceptorWins(request, response, claim);
            response.getOutputStream().write(utf8("12345678"));
        });

        assertEquals("12345678", stored().getBody());
        assertEquals("12345678", real.getContentAsString());
    }

    @Test
    void theRecordIsCompletedBeforeTheClientCanSeeTheBody() throws Exception {
        seed();
        MockHttpServletResponse real = new MockHttpServletResponse();
        AtomicInteger bytesSeenByTheClientWhenCompleting = new AtomicInteger(-1);
        store.onComplete = record -> bytesSeenByTheClientWhenCompleting.set(real.getContentAsByteArray().length);

        filter.doFilter(new MockHttpServletRequest("POST", "/orders"), real, (request, response) -> {
            interceptorWins(request, response, claim);
            response.getOutputStream().write(utf8("hello"));
        });

        assertEquals(0, bytesSeenByTheClientWhenCompleting.get(),
                "complete() must run while the body is still held back");
        assertEquals("hello", real.getContentAsString(), "and the body must be sent afterwards");
    }

    @Test
    void ifTheKeyExpiredMidRequestTheResponseStillGoesOutAndNothingElseHappens() throws Exception {
        seed();
        store.data.remove(KEY);

        MockHttpServletResponse real = run((request, response) -> {
            interceptorWins(request, response, claim);
            response.getOutputStream().write(utf8("done"));
        });

        assertEquals("done", real.getContentAsString());
        assertEquals(1, store.completes.get());
        assertEquals(0, store.deletes.get(), "a false from complete is not an error and needs no cleanup");
        assertEquals(0, store.claims.get(), "never re-claim an expired key");
    }

    @Test
    void aFailingCompleteIsSwallowedAndTheClientStillGetsTheResponse() throws Exception {
        seed();
        store.completeFailure = new RedisException("connection refused to 10.0.0.5:6379");

        MockHttpServletResponse real = run((request, response) -> {
            interceptorWins(request, response, claim);
            ((HttpServletResponse) response).setStatus(201);
            response.getOutputStream().write(utf8("created"));
        });

        assertEquals(201, real.getStatus());
        assertEquals("created", real.getContentAsString());
    }

    @Test
    void aFailingDeleteIsSwallowedAndTheClientStillGetsTheResponse() throws Exception {
        seed();
        store.deleteFailure = new RedisException("down");

        MockHttpServletResponse real = run((request, response) -> {
            interceptorWins(request, response, claim);
            ((HttpServletResponse) response).setStatus(503);
            response.getOutputStream().write(utf8("try later"));
        });

        assertEquals(503, real.getStatus());
        assertEquals("try later", real.getContentAsString());
    }

    static Stream<Throwable> failures() {
        return Stream.of(
                new RuntimeException("runtime"),
                new IllegalStateException("state"),
                new ServletException("servlet"),
                new IOException("io"),
                new Error("error"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void aFailingControllerDeletesTheClaimAndTheSameExceptionIsRethrown(Throwable failure) {
        seed();

        Throwable thrown = assertThrows(Throwable.class, () -> run((request, response) -> {
            interceptorWins(request, response, claim);
            sneakyThrow(failure);
        }));

        assertSame(failure, thrown);
        assertFalse(store.data.containsKey(KEY));
        assertEquals(1, store.deletes.get());
        assertEquals(0, store.completes.get());
    }

    @Test
    void bodyWrittenBeforeTheFailureIsNotSentToTheClient() {
        seed();
        MockHttpServletResponse real = new MockHttpServletResponse();

        assertThrows(IllegalStateException.class, () -> filter.doFilter(
                new MockHttpServletRequest("POST", "/orders"), real, (request, response) -> {
                    interceptorWins(request, response, claim);
                    response.getOutputStream().write(utf8("half an answer"));
                    throw new IllegalStateException("boom");
                }));

        assertEquals(0, real.getContentAsByteArray().length, "the container will write its own error instead");
    }

    @Test
    void aFailingDeleteNeverHidesTheControllersOwnException() {
        seed();
        store.deleteFailure = new RedisException("down");
        IllegalStateException failure = new IllegalStateException("the real problem");

        Throwable thrown = assertThrows(Throwable.class, () -> run((request, response) -> {
            interceptorWins(request, response, claim);
            throw failure;
        }));

        assertSame(failure, thrown, "the store's exception must not replace the controller's");
    }

    @Test
    void whenAsyncProcessingStartedNothingIsDecidedYet() throws Exception {
        seed();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
        MockHttpServletResponse real = new MockHttpServletResponse();

        filter.doFilter(request, real, (req, response) -> {
            interceptorWins(req, response, claim);
            ((MockHttpServletRequest) req).setAsyncStarted(true);
            response.getOutputStream().write(utf8("later"));
        });

        assertEquals(0, store.totalCalls(), "the real answer is not known yet");
        assertEquals(Status.PENDING, stored().getStatus());
        assertEquals("later", real.getContentAsString());
    }

    @Test
    void aNullStoreIsRejected() {
        assertThrows(NullPointerException.class, () -> new ResponseCaptureFilter(null));
        assertThrows(NullPointerException.class, () -> new ResponseCaptureFilter(null, 10));
    }

    @ParameterizedTest(name = "limit {0}")
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void aNegativeLimitIsRejected(int max) {
        assertThrows(IllegalArgumentException.class, () -> new ResponseCaptureFilter(store, max));
    }

    @Test
    void manyRequestsAtOnceEachGetTheirOwnResponseSavedAndSent() throws Exception {
        int threads = 32;
        List<IdempotencyRecord> claims = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            IdempotencyRecord own = new IdempotencyRecord("idempotency:v1:anonymous:k" + i, "fp-" + i, null, null, 0,
                    Duration.ofMinutes(5));
            assertTrue(store.claim(own));
            claims.add(own);
        }
        store.resetCounters();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                IdempotencyRecord own = claims.get(i);
                String body = "{\"n\":" + i + "}";
                futures.add(pool.submit(() -> {
                    go.await();
                    MockHttpServletResponse real = new MockHttpServletResponse();
                    filter.doFilter(new MockHttpServletRequest("POST", "/orders"), real, (request, response) -> {
                        interceptorWins(request, response, own);
                        ((HttpServletResponse) response).setStatus(201);
                        response.getOutputStream().write(utf8(body));
                    });
                    return real;
                }));
            }
            go.countDown();
            for (int i = 0; i < threads; i++) {
                MockHttpServletResponse real = futures.get(i).get(30, TimeUnit.SECONDS);
                assertEquals("{\"n\":" + i + "}", real.getContentAsString(), "no mix-up between requests");
                IdempotencyRecord saved = store.data.get(claims.get(i).getKey());
                assertEquals(Status.COMPLETED, saved.getStatus());
                assertEquals("{\"n\":" + i + "}", saved.getBody());
                assertEquals("fp-" + i, saved.getFingerprint());
            }
            assertEquals(threads, store.completes.get());
            assertEquals(0, store.deletes.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
