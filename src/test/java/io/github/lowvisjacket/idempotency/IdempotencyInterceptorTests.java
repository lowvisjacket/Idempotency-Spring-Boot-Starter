package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.annotation.Idempotent;
import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.exception.RedisException;
import io.github.lowvisjacket.idempotency.fingerprint.Fingerprint;
import io.github.lowvisjacket.idempotency.fingerprint.RequestWrapper;
import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;
import io.github.lowvisjacket.idempotency.model.Status;
import io.github.lowvisjacket.idempotency.store.IdempotencyStore;
import io.github.lowvisjacket.idempotency.store.StoredKeys;
import io.github.lowvisjacket.idempotency.web.CapturingResponse;
import io.github.lowvisjacket.idempotency.web.IdempotencyInterceptor;
import io.github.lowvisjacket.idempotency.web.ProblemResponseWriter;
import io.github.lowvisjacket.idempotency.web.ProblemType;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link IdempotencyInterceptor}, written before the logic (test plan A to I and L).
 *
 * <p>Rules these tests pin down (the class Javadoc has the full list):
 * <ul>
 *   <li>No {@code @Idempotent} (method, else class) means no store calls at all, whatever the request looks like.</li>
 *   <li>Order of checks: key (400), oversized body (413), TTL, then the store. Nothing reaches the store before
 *       the key is valid, and a bad TTL annotation never leaves a record behind.</li>
 *   <li>Claim wins: run. Claim lost: PENDING gives 409 whatever the body is; COMPLETED with another fingerprint
 *       gives 422 and never shows the saved response; COMPLETED with the same fingerprint replays.</li>
 *   <li>A lost claim whose record has vanished is retried once, then 409. No loops.</li>
 *   <li>A store failure fails closed (503 + Retry-After) and never leaks the exception message.</li>
 *   <li>The caller (principal) is part of the stored key.</li>
 * </ul>
 *
 * <p>The store is an in-memory fake with the same atomic claim as Redis, plus call counters and switches to make
 * it fail or lose races on purpose. Replay is tested on status, body and the marker header only; storing and
 * replaying response headers waits for the multi-header record change.
 */
class IdempotencyInterceptorTests {

    // =====================================================================================================
    // Test controllers
    // =====================================================================================================

    public static class Controller {
        @Idempotent public void create() { }
        @Idempotent(ttl = "90s") public void shortTtl() { }
        @Idempotent(ttl = "banana") public void badTtl() { }
        @Idempotent(header = "X-Request-Id") public void customHeader() { }
        public void plain() { }
    }

    @Idempotent(ttl = "2d")
    public static class ClassLevelController {
        public void inherited() { }
        @Idempotent(ttl = "1h") public void override() { }
    }

    // =====================================================================================================
    // Fake store
    // =====================================================================================================

    /** In-memory store with an atomic claim, call counters, and switches to fail or lose on purpose. */
    static class FakeStore implements IdempotencyStore {
        final Map<String, IdempotencyRecord> data = new ConcurrentHashMap<>();
        final List<IdempotencyRecord> claimed = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger claims = new AtomicInteger();
        final AtomicInteger finds = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();   // complete + delete
        final AtomicInteger completes = new AtomicInteger();
        final AtomicInteger deletes = new AtomicInteger();
        volatile RuntimeException completeFailure;
        volatile RuntimeException deleteFailure;
        /** Called inside {@code complete}, before the record is stored (to look at the world at that moment). */
        volatile java.util.function.Consumer<IdempotencyRecord> onComplete;
        final AtomicInteger others = new AtomicInteger();   // exists + getRemainingTtl
        volatile RuntimeException claimFailure;
        volatile RuntimeException findFailure;
        /** The next N claim calls answer false without storing anything (a lost race, or a record that vanished). */
        final AtomicInteger claimsToLose = new AtomicInteger();

        int totalCalls() {
            return claims.get() + finds.get() + writes.get() + others.get();
        }

        void resetCounters() {
            claims.set(0);
            finds.set(0);
            writes.set(0);
            completes.set(0);
            deletes.set(0);
            others.set(0);
            claimed.clear();
        }

        private static IdempotencyRecord copy(IdempotencyRecord r) {
            IdempotencyRecord c = new IdempotencyRecord(r.getKey(), r.getFingerprint(), r.getContentType(), r.getBody(),
                    r.getHttpStatus(), r.getTtl());
            c.setStatus(r.getStatus());
            return c;
        }

        @Override
        public boolean claim(IdempotencyRecord record) {
            claims.incrementAndGet();
            if (claimFailure != null) throw claimFailure;
            claimed.add(copy(record));
            if (claimsToLose.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) return false;
            return data.putIfAbsent(record.getKey(), copy(record)) == null;
        }

        @Override
        public Optional<IdempotencyRecord> find(String key) {
            finds.incrementAndGet();
            if (findFailure != null) throw findFailure;
            IdempotencyRecord found = data.get(key);
            if (found == null) return Optional.empty();
            IdempotencyRecord c = copy(found);
            c.setTtl(null);
            return Optional.of(c);
        }

        @Override
        public boolean complete(IdempotencyRecord record) {
            writes.incrementAndGet();
            completes.incrementAndGet();
            if (completeFailure != null) throw completeFailure;
            java.util.function.Consumer<IdempotencyRecord> hook = onComplete;
            if (hook != null) hook.accept(record);
            return data.computeIfPresent(record.getKey(), (k, old) -> copy(record)) != null;
        }

        @Override
        public boolean delete(String key) {
            writes.incrementAndGet();
            deletes.incrementAndGet();
            if (deleteFailure != null) throw deleteFailure;
            return data.remove(key) != null;
        }

        @Override
        public boolean exists(String key) {
            others.incrementAndGet();
            return data.containsKey(key);
        }

        @Override
        public Optional<Duration> getRemainingTtl(String key) {
            others.incrementAndGet();
            return Optional.empty();
        }
    }

    // =====================================================================================================
    // Fixtures and helpers
    // =====================================================================================================

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final FakeStore store = new FakeStore();
    private final Fingerprint fingerprint = new Fingerprint();
    private final IdempotencyInterceptor interceptor =
            new IdempotencyInterceptor(store, fingerprint, new ProblemResponseWriter(mapper));

    private static final String BODY = "{\"amount\":100}";

    private static HandlerMethod handler(Object bean, String method) throws NoSuchMethodException {
        return new HandlerMethod(bean, method);
    }

    private static HandlerMethod create() throws NoSuchMethodException {
        return handler(new Controller(), "create");
    }

    private static MockHttpServletRequest post(String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static MockHttpServletRequest validPost(String key) {
        MockHttpServletRequest request = post("/orders", BODY);
        request.addHeader("Idempotency-Key", key);
        return request;
    }

    private static MockHttpServletRequest asUser(MockHttpServletRequest request, String name) {
        request.setUserPrincipal(() -> name);
        return request;
    }

    /** Wraps the request the way the filter does for POST, PUT and PATCH. */
    private static jakarta.servlet.http.HttpServletRequest asFilterWould(MockHttpServletRequest request) throws IOException {
        return switch (request.getMethod()) {
            case "POST", "PUT", "PATCH" -> new RequestWrapper(request);
            default -> request;
        };
    }

    private boolean run(MockHttpServletRequest request, MockHttpServletResponse response, HandlerMethod handler)
            throws Exception {
        return interceptor.preHandle(asFilterWould(request), response, handler);
    }

    private String fingerprintOf(MockHttpServletRequest request) throws IOException {
        return fingerprint.hash(new RequestWrapper(request));
    }

    private static String storedKey(String caller, String key) {
        return StoredKeys.build(caller, key);
    }

    /** Puts a finished record in the store, as if an earlier identical request had completed. */
    private void seedCompleted(String caller, String key, MockHttpServletRequest sameAs, int status, String body)
            throws IOException {
        seedCompleted(caller, key, sameAs, status, null, body);
    }

    private void seedCompleted(String caller, String key, MockHttpServletRequest sameAs, int status,
                               String contentType, String body) throws IOException {
        IdempotencyRecord record = new IdempotencyRecord(storedKey(caller, key), fingerprintOf(sameAs), contentType,
                body, status, Duration.ofHours(1));
        record.setStatus(Status.COMPLETED);
        assertTrue(store.claim(record));
        store.resetCounters();
    }

    private void seedPending(String caller, String key, MockHttpServletRequest sameAs) throws IOException {
        IdempotencyRecord record = new IdempotencyRecord(storedKey(caller, key), fingerprintOf(sameAs), null, null,
                0, Duration.ofHours(1));
        assertTrue(store.claim(record));
        store.resetCounters();
    }

    private Map<?, ?> problem(MockHttpServletResponse response) throws Exception {
        return mapper.readValue(response.getContentAsByteArray(), Map.class);
    }

    private void assertProblem(MockHttpServletResponse response, ProblemType expected) throws Exception {
        assertEquals(expected.status(), response.getStatus());
        assertTrue(response.getContentType().startsWith("application/problem+json"), response.getContentType());
        assertEquals(expected.type(), problem(response).get("type"));
    }

    private void assertRetryAfter(MockHttpServletResponse response, Duration expected) {
        assertEquals(Long.toString(expected.toSeconds()), response.getHeader("Retry-After"));
    }

    private void assertNothingWritten(MockHttpServletResponse response) {
        assertEquals(200, response.getStatus());
        assertEquals(0, response.getContentAsByteArray().length);
        assertNull(response.getHeader(IdempotencyDefaults.REPLAY_HEADER));
    }

    // =====================================================================================================
    // Endpoints that are not @Idempotent are never touched
    // =====================================================================================================

    @Test
    void aPlainEndpointRunsAndNeverTouchesTheStore() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(run(post("/orders", BODY), response, handler(new Controller(), "plain")));

        assertEquals(0, store.totalCalls());
        assertNothingWritten(response);
    }

    @ParameterizedTest(name = "plain endpoint ignores bad key [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "has space", "bad\nkey", "nön-ascii"})
    void aPlainEndpointDoesNotCareWhatTheKeyHeaderSays(String key) throws Exception {
        MockHttpServletRequest request = post("/orders", BODY);
        if (key != null) request.addHeader("Idempotency-Key", key);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(run(request, response, handler(new Controller(), "plain")));

        assertEquals(0, store.totalCalls());
    }

    @Test
    void aPlainEndpointIgnoresTheOversizedFlagAndAFailingStore() throws Exception {
        store.claimFailure = new RedisException("down");
        store.findFailure = new RedisException("down");
        MockHttpServletRequest request = validPost("k1");
        request.setAttribute(IdempotencyDefaults.BODY_EXCEEDS_CAP_ATTRIBUTE, Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(run(request, response, handler(new Controller(), "plain")));

        assertEquals(0, store.totalCalls());
        assertNothingWritten(response);
    }

    @Test
    void aHandlerThatIsNotAControllerMethodIsIgnored() throws Exception {
        // For example a static resource handler: no HandlerMethod, so nothing to check and no key needed.
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(new MockHttpServletRequest("GET", "/logo.png"), response, "not a HandlerMethod"));
        assertTrue(interceptor.preHandle(new MockHttpServletRequest("GET", "/logo.png"), response, null));

        assertEquals(0, store.totalCalls());
    }

    // =====================================================================================================
    // A. Key validation: 400, the controller does not run, the store is never touched
    // =====================================================================================================

    @Test
    void aMissingKeyIsRefusedWith400() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(post("/orders", BODY), response, create()));

        assertProblem(response, ProblemType.INVALID_KEY);
        assertEquals(0, store.totalCalls());
    }

    @ParameterizedTest(name = "invalid key #{index} is refused")
    @ValueSource(strings = {
            "", " ", "   ", "\t", "has space", " leading", "trailing ", "tab\there", "line\nbreak", "cr\rhere",
            "nul\u0000byte", "escape\u001bchar", "nön-ascii", "emoji-😀", "ключ", "del\u007fchar"
    })
    void anInvalidKeyIsRefusedWith400AndNeverReachesTheStore(String key) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(validPost(key), response, create()));

        assertProblem(response, ProblemType.INVALID_KEY);
        assertEquals(0, store.totalCalls());
    }

    @Test
    void aTooLongKeyIsRefusedWith400() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(validPost("k".repeat(IdempotencyDefaults.MAX_KEY_LENGTH + 1)), response, create()));

        assertProblem(response, ProblemType.INVALID_KEY);
        assertEquals(0, store.totalCalls());
    }

    @Test
    void aKeyAtTheLengthLimitIsAccepted() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(run(validPost("k".repeat(IdempotencyDefaults.MAX_KEY_LENGTH)), response, create()));
    }

    @Test
    void theErrorNeverRepeatsTheBadKey() throws Exception {
        String evil = "bad key <script>alert(1)</script>\r\nSet-Cookie: x=y";
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(validPost(evil), response, create());

        String body = response.getContentAsString();
        assertFalse(body.contains("script"), body);
        assertFalse(body.contains("Set-Cookie"), body);
        assertFalse(body.contains("bad key"), body);
    }

    @Test
    void twoKeyHeadersAreAmbiguousAndRefusedEvenIfIdentical() throws Exception {
        MockHttpServletRequest request = post("/orders", BODY);
        request.addHeader("Idempotency-Key", "same");
        request.addHeader("Idempotency-Key", "same");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(request, response, create()));

        assertProblem(response, ProblemType.INVALID_KEY);
        assertEquals(0, store.totalCalls());
    }

    @Test
    void theHeaderNameIsCaseInsensitive() throws Exception {
        MockHttpServletRequest request = post("/orders", BODY);
        request.addHeader("idempotency-key", "lower-case-name");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(run(request, response, create()));

        assertEquals(storedKey(null, "lower-case-name"), store.claimed.get(0).getKey());
    }

    @Test
    void theKeyIsUsedExactlyAsSentWithNoTrimmingOrCaseChange() throws Exception {
        assertTrue(run(validPost("Abc-123"), new MockHttpServletResponse(), create()));
        assertTrue(run(validPost("abc-123"), new MockHttpServletResponse(), create()));

        assertEquals(2, store.data.size(), "keys that differ only by case are different keys");
    }

    @Test
    void aCustomHeaderNameIsHonoredAndTheDefaultHeaderIsIgnored() throws Exception {
        HandlerMethod custom = handler(new Controller(), "customHeader");
        MockHttpServletRequest request = post("/orders", BODY);
        request.addHeader("Idempotency-Key", "bad key");   // default header: must be ignored
        request.addHeader("X-Request-Id", "good-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(run(request, response, custom));

        assertEquals(storedKey(null, "good-key"), store.claimed.get(0).getKey());
    }

    @Test
    void aCustomHeaderEndpointWithOnlyTheDefaultHeaderIsMissingItsKey() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(validPost("present-but-wrong-header"), response, handler(new Controller(), "customHeader")));

        assertProblem(response, ProblemType.INVALID_KEY);
        assertEquals(0, store.totalCalls());
    }

    @Test
    void anInvalidKeyIsReportedBeforeAnOversizedBody() throws Exception {
        MockHttpServletRequest request = validPost("bad key");
        request.setAttribute(IdempotencyDefaults.BODY_EXCEEDS_CAP_ATTRIBUTE, Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(request, response, create()));

        assertProblem(response, ProblemType.INVALID_KEY);
    }

    @Test
    void anInvalidKeyIsReportedEvenWhenTheStoreIsDown() throws Exception {
        store.claimFailure = new RedisException("down");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(validPost("bad key"), response, create()));

        assertProblem(response, ProblemType.INVALID_KEY);
        assertEquals(0, store.totalCalls());
    }

    // =====================================================================================================
    // B. A new key: claim, store a PENDING record, run the controller
    // =====================================================================================================

    @Test
    void aNewKeyIsClaimedOnceAndTheControllerRunsWithTheResponseUntouched() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(run(validPost("k1"), response, create()));

        assertEquals(1, store.claims.get());
        assertEquals(0, store.finds.get(), "a won claim needs no lookup");
        assertEquals(0, store.writes.get());
        assertNothingWritten(response);
    }

    @Test
    void theClaimedRecordIsPendingWithTheStoredKeyFingerprintAndTtl() throws Exception {
        MockHttpServletRequest request = validPost("k1");

        run(request, new MockHttpServletResponse(), create());

        IdempotencyRecord record = store.claimed.get(0);
        assertEquals(storedKey(null, "k1"), record.getKey());
        assertEquals(Status.PENDING, record.getStatus());
        // A mock request body can be read only once, so the expected value comes from an identical fresh request.
        assertEquals(fingerprintOf(validPost("k1")), record.getFingerprint());
        assertEquals(Duration.ofHours(24), record.getTtl(), "default ttl");
        assertNull(record.getBody(), "no response exists yet");
        assertEquals(0, record.getHttpStatus());
    }

    @Test
    void theTtlComesFromTheAnnotation() throws Exception {
        run(validPost("k1"), new MockHttpServletResponse(), handler(new Controller(), "shortTtl"));

        assertEquals(Duration.ofSeconds(90), store.claimed.get(0).getTtl());
    }

    @Test
    void aClassLevelAnnotationAppliesToItsMethods() throws Exception {
        assertTrue(run(validPost("k1"), new MockHttpServletResponse(), handler(new ClassLevelController(), "inherited")));

        assertEquals(Duration.ofDays(2), store.claimed.get(0).getTtl());
    }

    @Test
    void aMethodLevelAnnotationBeatsTheClassLevelOne() throws Exception {
        assertTrue(run(validPost("k1"), new MockHttpServletResponse(), handler(new ClassLevelController(), "override")));

        assertEquals(Duration.ofHours(1), store.claimed.get(0).getTtl());
    }

    @Test
    void aBadTtlInTheAnnotationFailsBeforeAnythingIsStored() throws Exception {
        // If the claim happened first, a PENDING record would sit in the store until it expired.
        assertThrows(IllegalArgumentException.class,
                () -> run(validPost("k1"), new MockHttpServletResponse(), handler(new Controller(), "badTtl")));

        assertEquals(0, store.totalCalls());
        assertTrue(store.data.isEmpty());
    }

    @Test
    void aGetRequestThatTheFilterDoesNotWrapStillWorks() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/orders");
        get.setQueryString("page=2");
        get.addHeader("Idempotency-Key", "g1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(get, response, create()), "passed unwrapped on purpose");

        MockHttpServletRequest same = new MockHttpServletRequest("GET", "/orders");
        same.setQueryString("page=2");
        assertEquals(fingerprintOf(same), store.claimed.get(0).getFingerprint());
    }

    @Test
    void theControllerCanStillReadTheBodyAfterTheInterceptorRan() throws Exception {
        MockHttpServletRequest request = validPost("k1");
        jakarta.servlet.http.HttpServletRequest wrapped = new RequestWrapper(request);

        assertTrue(interceptor.preHandle(wrapped, new MockHttpServletResponse(), create()));

        assertEquals(BODY, new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(BODY, wrapped.getReader().readLine());
    }

    // =====================================================================================================
    // C. A key that is still PENDING: 409, nothing changes
    // =====================================================================================================

    @Test
    void aPendingKeyIsRefusedWith409AndARetryAfter() throws Exception {
        seedPending(null, "k1", validPost("k1"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(validPost("k1"), response, create()));

        assertProblem(response, ProblemType.REQUEST_IN_PROGRESS);
        assertRetryAfter(response, IdempotencyDefaults.PENDING_RETRY_AFTER);
        assertEquals(0, store.writes.get(), "a refused request must not change the stored record");
        assertEquals(Status.PENDING, store.data.get(storedKey(null, "k1")).getStatus());
    }

    @Test
    void aPendingKeyIsStill409WhenTheSecondRequestHasADifferentBody() throws Exception {
        seedPending(null, "k1", validPost("k1"));
        MockHttpServletRequest different = post("/orders", "{\"amount\":999}");
        different.addHeader("Idempotency-Key", "k1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(different, response, create()));

        assertProblem(response, ProblemType.REQUEST_IN_PROGRESS);
    }

    @Test
    void thePendingResponseDoesNotReplayAnything() throws Exception {
        seedPending(null, "k1", validPost("k1"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(validPost("k1"), response, create());

        assertNull(response.getHeader(IdempotencyDefaults.REPLAY_HEADER));
    }

    // =====================================================================================================
    // D. COMPLETED with the same fingerprint: replay
    // =====================================================================================================

    @ParameterizedTest(name = "replays status {0}")
    @ValueSource(ints = {200, 201, 202, 204, 400, 404, 409, 418, 422})
    void aCompletedRequestIsReplayedWithItsSavedStatusAndBody(int status) throws Exception {
        seedCompleted(null, "k1", validPost("k1"), status, "{\"id\":7}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(validPost("k1"), response, create()), "the controller must not run again");

        assertEquals(status, response.getStatus());
        assertEquals("{\"id\":7}", response.getContentAsString());
        assertEquals("true", response.getHeader(IdempotencyDefaults.REPLAY_HEADER));
        assertEquals(0, store.writes.get());
    }

    @Test
    void aReplayWithNoSavedBodyWritesAnEmptyBody() throws Exception {
        seedCompleted(null, "k1", validPost("k1"), 204, null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(validPost("k1"), response, create()));

        assertEquals(204, response.getStatus());
        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void aReplayKeepsNonAsciiTextIntact() throws Exception {
        String text = "{\"name\":\"José ✓ 😀 日本語\"}";
        seedCompleted(null, "k1", validPost("k1"), 200, text);
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(validPost("k1"), response, create());

        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), response.getContentAsByteArray());
    }

    @ParameterizedTest(name = "replays Content-Type {0}")
    @ValueSource(strings = {"application/json", "application/json;charset=UTF-8", "text/plain;charset=UTF-8",
            "application/problem+json", "text/html; charset=utf-8", "application/vnd.api+json"})
    void aReplayIsSentWithTheSavedContentType(String contentType) throws Exception {
        seedCompleted(null, "k1", validPost("k1"), 201, contentType, "{\"id\":7}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(validPost("k1"), response, create());

        assertEquals(contentType, response.getContentType());
        assertEquals("{\"id\":7}", response.getContentAsString());
    }

    @Test
    void aReplayWithNonAsciiTextAndTheSavedCharsetIsByteForByteTheOriginal() throws Exception {
        String text = "José ✓ 😀";
        seedCompleted(null, "k1", validPost("k1"), 200, "text/plain;charset=UTF-8", text);
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(validPost("k1"), response, create());

        assertEquals("text/plain;charset=UTF-8", response.getContentType());
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), response.getContentAsByteArray());
    }

    @Test
    void aReplayWithNoSavedContentTypeSendsNoneOfItsOwn() throws Exception {
        seedCompleted(null, "k1", validPost("k1"), 204, null, null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(validPost("k1"), response, create());

        assertNull(response.getContentType());
    }

    @Test
    void aReplayWithAnEmptyBodyStillSendsTheSavedContentType() throws Exception {
        seedCompleted(null, "k1", validPost("k1"), 200, "application/json", null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(validPost("k1"), response, create());

        assertEquals("application/json", response.getContentType());
        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void aReplayDoesNotChangeTheSavedContentType() throws Exception {
        seedCompleted(null, "k1", validPost("k1"), 200, "application/json", "{}");

        run(validPost("k1"), new MockHttpServletResponse(), create());

        assertEquals("application/json", store.data.get(storedKey(null, "k1")).getContentType());
    }

    @Test
    void replayingTwiceGivesTheSameAnswerBothTimes() throws Exception {
        seedCompleted(null, "k1", validPost("k1"), 201, "{\"id\":7}");

        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();
        assertFalse(run(validPost("k1"), first, create()));
        assertFalse(run(validPost("k1"), second, create()));

        assertEquals(first.getStatus(), second.getStatus());
        assertArrayEquals(first.getContentAsByteArray(), second.getContentAsByteArray());
    }

    @Test
    void aBodyWrittenWithTheOtherUnicodeFormIsStillTheSameRequest() throws Exception {
        String composed = "{\"name\":\"José\"}";
        String decomposed = "{\"name\":\"José\"}";
        MockHttpServletRequest original = post("/orders", composed);
        original.addHeader("Idempotency-Key", "k1");
        seedCompleted(null, "k1", original, 201, "saved");
        MockHttpServletRequest retry = post("/orders", decomposed);
        retry.addHeader("Idempotency-Key", "k1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(retry, response, create()));

        assertEquals(201, response.getStatus());
        assertEquals("saved", response.getContentAsString());
    }

    // =====================================================================================================
    // E. COMPLETED with a different fingerprint: 422, and the saved response is never shown
    // =====================================================================================================

    private static MockHttpServletRequest variant(String method, String path, String query, String body, String key) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (query != null) request.setQueryString(query);
        request.addHeader("Idempotency-Key", key);
        return request;
    }

    @ParameterizedTest(name = "{0} gives 422")
    @CsvSource(delimiter = '|', value = {
            "another body             | POST | /orders       | ''     | {\"amount\":101}",
            "whitespace in the body   | POST | /orders       | ''     | {\"amount\": 100}",
            "empty body               | POST | /orders       | ''     | ''",
            "another path             | POST | /payments     | ''     | {\"amount\":100}",
            "trailing slash path      | POST | /orders/      | ''     | {\"amount\":100}",
            "a query string           | POST | /orders       | dry=1  | {\"amount\":100}",
            "another method           | PUT  | /orders       | ''     | {\"amount\":100}",
            "same text other case     | POST | /Orders       | ''     | {\"amount\":100}"
    })
    void aReusedKeyOnADifferentRequestIsRefusedWith422(String name, String method, String path, String query,
                                                        String body) throws Exception {
        seedCompleted(null, "k1", variant("POST", "/orders", null, BODY, "k1"), 201, "SECRET-SAVED-RESPONSE");
        MockHttpServletRequest second = variant(method, path, query.isEmpty() ? null : query, body.equals("''") ? "" : body, "k1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(second, response, create()));

        assertProblem(response, ProblemType.KEY_REUSED);
        assertFalse(response.getContentAsString().contains("SECRET-SAVED-RESPONSE"), "never leak the saved response");
        assertNull(response.getHeader(IdempotencyDefaults.REPLAY_HEADER));
        assertEquals(0, store.writes.get());
        assertEquals(Status.COMPLETED, store.data.get(storedKey(null, "k1")).getStatus(), "record untouched");
    }

    @Test
    void aReusedKeyOnAnotherEndpointOfTheSameCallerIsRefusedWith422() throws Exception {
        seedCompleted(null, "k1", validPost("k1"), 201, "saved");
        MockHttpServletRequest other = variant("POST", "/refunds", null, BODY, "k1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(other, response, handler(new Controller(), "create")));

        assertProblem(response, ProblemType.KEY_REUSED);
    }

    // =====================================================================================================
    // F. Caller identity
    // =====================================================================================================

    @Test
    void twoCallersSendingTheSameKeyAndRequestBothRun() throws Exception {
        assertTrue(run(asUser(validPost("k1"), "alice"), new MockHttpServletResponse(), create()));
        assertTrue(run(asUser(validPost("k1"), "bob"), new MockHttpServletResponse(), create()));

        assertEquals(2, store.data.size());
        assertTrue(store.data.containsKey(storedKey("alice", "k1")));
        assertTrue(store.data.containsKey(storedKey("bob", "k1")));
    }

    @Test
    void anotherCallersCompletedResponseIsNeverReplayed() throws Exception {
        seedCompleted("alice", "k1", validPost("k1"), 201, "ALICES-ORDER");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(run(asUser(validPost("k1"), "bob"), response, create()), "bob runs his own request");

        assertFalse(response.getContentAsString().contains("ALICES-ORDER"));
        assertNothingWritten(response);
    }

    @Test
    void theSameCallerRetryingGetsTheReplay() throws Exception {
        seedCompleted("alice", "k1", validPost("k1"), 201, "ALICES-ORDER");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(asUser(validPost("k1"), "alice"), response, create()));

        assertEquals("ALICES-ORDER", response.getContentAsString());
    }

    @Test
    void callerNamesAreCaseSensitive() throws Exception {
        assertTrue(run(asUser(validPost("k1"), "Alice"), new MockHttpServletResponse(), create()));
        assertTrue(run(asUser(validPost("k1"), "alice"), new MockHttpServletResponse(), create()));

        assertEquals(2, store.data.size());
    }

    @Test
    void anonymousCallersShareOneNamespace() throws Exception {
        assertTrue(run(validPost("k1"), new MockHttpServletResponse(), create()));
        MockHttpServletResponse second = new MockHttpServletResponse();

        assertFalse(run(validPost("k1"), second, create()));

        assertProblem(second, ProblemType.REQUEST_IN_PROGRESS);
    }

    @Test
    void aPrincipalWithABlankNameIsTreatedAsAnonymous() throws Exception {
        assertTrue(run(asUser(validPost("k1"), "  "), new MockHttpServletResponse(), create()));

        assertEquals(storedKey(null, "k1"), store.claimed.get(0).getKey());
    }

    @Test
    void anAnonymousRequestDoesNotCollideWithAUsersRecordOfTheSameKey() throws Exception {
        seedCompleted("alice", "k1", validPost("k1"), 201, "ALICES-ORDER");

        assertTrue(run(validPost("k1"), new MockHttpServletResponse(), create()));
    }

    // =====================================================================================================
    // G. Oversized body flagged by the filter
    // =====================================================================================================

    @Test
    void anOversizedBodyOnAnIdempotentEndpointIsRefusedWith413() throws Exception {
        MockHttpServletRequest request = validPost("k1");
        request.setAttribute(IdempotencyDefaults.BODY_EXCEEDS_CAP_ATTRIBUTE, Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, create()));

        assertProblem(response, ProblemType.BODY_TOO_LARGE);
        assertEquals(0, store.totalCalls(), "no fingerprint exists, so nothing can be claimed");
    }

    @Test
    void aFlagThatIsNotTrueDoesNotRefuseTheRequest() throws Exception {
        MockHttpServletRequest request = validPost("k1");
        request.setAttribute(IdempotencyDefaults.BODY_EXCEEDS_CAP_ATTRIBUTE, Boolean.FALSE);

        assertTrue(run(request, new MockHttpServletResponse(), create()));
    }

    // =====================================================================================================
    // H. The store fails: fail closed
    // =====================================================================================================

    @Test
    void whenClaimingFailsTheAnswerIs503AndTheControllerDoesNotRun() throws Exception {
        store.claimFailure = new RedisException("connection refused to 10.0.0.5:6379 password=hunter2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(validPost("k1"), response, create()));

        assertProblem(response, ProblemType.STORE_UNAVAILABLE);
        assertRetryAfter(response, IdempotencyDefaults.STORE_RETRY_AFTER);
    }

    @Test
    void theStoreErrorMessageNeverReachesTheClient() throws Exception {
        store.claimFailure = new RedisException("connection refused to 10.0.0.5:6379 password=hunter2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(validPost("k1"), response, create());

        String body = response.getContentAsString();
        assertFalse(body.contains("10.0.0.5"), body);
        assertFalse(body.contains("hunter2"), body);
        assertFalse(body.contains("RedisException"), body);
    }

    @Test
    void anyRuntimeExceptionFromTheStoreIsTreatedAsUnavailable() throws Exception {
        store.claimFailure = new IllegalStateException("pool exhausted");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(validPost("k1"), response, create()));

        assertProblem(response, ProblemType.STORE_UNAVAILABLE);
    }

    @Test
    void whenTheLookupAfterALostClaimFailsTheAnswerIs503() throws Exception {
        seedPending(null, "k1", validPost("k1"));
        store.findFailure = new RedisException("timeout");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(validPost("k1"), response, create()));

        assertProblem(response, ProblemType.STORE_UNAVAILABLE);
        assertRetryAfter(response, IdempotencyDefaults.STORE_RETRY_AFTER);
    }

    @Test
    void aFailingStoreIsNotRetriedForeverOrWrittenTo() throws Exception {
        store.claimFailure = new RedisException("down");

        run(validPost("k1"), new MockHttpServletResponse(), create());

        assertTrue(store.claims.get() <= 1, "a failed claim must not be hammered");
        assertEquals(0, store.writes.get());
    }

    // =====================================================================================================
    // I. Races
    // =====================================================================================================

    @Test
    void aLostClaimWhoseRecordHasVanishedIsRetriedOnceAndThenRuns() throws Exception {
        store.claimsToLose.set(1);   // first claim "loses", but no record is there when we look
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(run(validPost("k1"), response, create()));

        assertEquals(2, store.claims.get());
        assertEquals(1, store.finds.get());
        assertNothingWritten(response);
    }

    @Test
    void ifTheRetryIsLostTooTheAnswerIs409AndNothingLoopsForever() throws Exception {
        store.claimsToLose.set(1000);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(validPost("k1"), response, create()));

        assertProblem(response, ProblemType.REQUEST_IN_PROGRESS);
        assertEquals(2, store.claims.get(), "one try plus one retry, never more");
    }

    private static final int THREADS = 32;

    /** Runs the same job on many threads released at the same moment; returns each thread's result. */
    private <T> List<T> inParallel(Callable<T> job) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch ready = new CountDownLatch(THREADS);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return job.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void manySimultaneousRequestsWithOneKeyRunTheControllerExactlyOnce() throws Exception {
        List<MockHttpServletResponse> responses = inParallel(() -> {
            MockHttpServletResponse response = new MockHttpServletResponse();
            boolean runs = run(validPost("same-key"), response, create());
            return runs ? null : response;
        }).stream().filter(r -> r != null).toList();

        assertEquals(THREADS - 1, responses.size(), "exactly one request may run the controller");
        for (MockHttpServletResponse response : responses) {
            assertProblem(response, ProblemType.REQUEST_IN_PROGRESS);
        }
        assertEquals(1, store.data.size());
    }

    @Test
    void manySimultaneousRequestsWithDifferentKeysAllRun() throws Exception {
        AtomicInteger next = new AtomicInteger();

        List<Boolean> results = inParallel(() ->
                run(validPost("key-" + next.incrementAndGet()), new MockHttpServletResponse(), create()));

        assertTrue(results.stream().allMatch(r -> r));
        assertEquals(THREADS, store.data.size());
    }

    @Test
    void manySimultaneousRequestsWithOneKeyFromDifferentCallersAllRun() throws Exception {
        AtomicInteger next = new AtomicInteger();

        List<Boolean> results = inParallel(() ->
                run(asUser(validPost("same-key"), "user-" + next.incrementAndGet()), new MockHttpServletResponse(), create()));

        assertTrue(results.stream().allMatch(r -> r));
        assertEquals(THREADS, store.data.size());
    }

    @Test
    void manySimultaneousRetriesOfAFinishedRequestAllGetTheSameReplay() throws Exception {
        seedCompleted(null, "k1", validPost("k1"), 201, "{\"id\":7}");

        List<MockHttpServletResponse> responses = inParallel(() -> {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(run(validPost("k1"), response, create()));
            return response;
        });

        for (MockHttpServletResponse response : responses) {
            assertEquals(201, response.getStatus());
            assertEquals("{\"id\":7}", response.getContentAsString());
        }
    }

    // =====================================================================================================
    // M. Hand-over to the response capture: the claim attribute and startCapturing()
    // =====================================================================================================

    private static IdempotencyRecord claimAttribute(MockHttpServletRequest request) {
        return (IdempotencyRecord) request.getAttribute(IdempotencyDefaults.CLAIM_ATTRIBUTE);
    }

    @Test
    void aWonClaimIsHandedToTheResponseFilterThroughTheRequest() throws Exception {
        MockHttpServletRequest request = validPost("k1");

        assertTrue(run(request, new MockHttpServletResponse(), create()));

        IdempotencyRecord handedOver = claimAttribute(request);
        assertNotNull(handedOver, "without this the filter cannot know which record to complete");
        assertEquals(storedKey(null, "k1"), handedOver.getKey());
        assertEquals(Status.PENDING, handedOver.getStatus());
        assertEquals(store.claimed.get(0).getFingerprint(), handedOver.getFingerprint());
        assertEquals(Duration.ofHours(24), handedOver.getTtl(), "completion must reset the ttl to the annotation's");
    }

    @Test
    void aClaimWonOnTheRetryIsHandedOverToo() throws Exception {
        store.claimsToLose.set(1);
        MockHttpServletRequest request = validPost("k1");

        assertTrue(run(request, new MockHttpServletResponse(), create()));

        assertNotNull(claimAttribute(request));
    }

    @Test
    void aPendingRefusalHandsNothingOver() throws Exception {
        seedPending(null, "k1", validPost("k1"));
        MockHttpServletRequest request = validPost("k1");

        assertFalse(run(request, new MockHttpServletResponse(), create()));

        assertNull(claimAttribute(request), "this request owns nothing; completing would damage the real owner's record");
    }

    @Test
    void aReusedKeyRefusalHandsNothingOver() throws Exception {
        seedCompleted(null, "k1", validPost("k1"), 201, "saved");
        MockHttpServletRequest request = variant("POST", "/other", null, BODY, "k1");

        assertFalse(run(request, new MockHttpServletResponse(), create()));

        assertNull(claimAttribute(request));
    }

    @Test
    void aReplayHandsNothingOver() throws Exception {
        seedCompleted(null, "k1", validPost("k1"), 201, "saved");
        MockHttpServletRequest request = validPost("k1");

        assertFalse(run(request, new MockHttpServletResponse(), create()));

        assertNull(claimAttribute(request), "a replay must never complete or delete the stored record");
    }

    @Test
    void anInvalidKeyHandsNothingOver() throws Exception {
        MockHttpServletRequest request = validPost("bad key");

        assertFalse(run(request, new MockHttpServletResponse(), create()));

        assertNull(claimAttribute(request));
    }

    @Test
    void aStoreFailureHandsNothingOver() throws Exception {
        store.claimFailure = new RedisException("down");
        MockHttpServletRequest request = validPost("k1");

        assertFalse(run(request, new MockHttpServletResponse(), create()));

        assertNull(claimAttribute(request));
    }

    @Test
    void aPlainEndpointHandsNothingOver() throws Exception {
        MockHttpServletRequest request = validPost("k1");

        assertTrue(run(request, new MockHttpServletResponse(), handler(new Controller(), "plain")));

        assertNull(claimAttribute(request));
    }

    @Test
    void aWonClaimStartsCapturingTheResponse() throws Exception {
        CapturingResponse capture = new CapturingResponse(new MockHttpServletResponse(), 1024);

        assertTrue(interceptor.preHandle(asFilterWould(validPost("k1")), capture, create()));

        assertTrue(capture.isCapturing(), "the body must be held back so it can be saved before the client sees it");
    }

    @Test
    void aClaimWonOnTheRetryStartsCapturingToo() throws Exception {
        store.claimsToLose.set(1);
        CapturingResponse capture = new CapturingResponse(new MockHttpServletResponse(), 1024);

        assertTrue(interceptor.preHandle(asFilterWould(validPost("k1")), capture, create()));

        assertTrue(capture.isCapturing());
    }

    @Test
    void aRefusalDoesNotStartCapturing() throws Exception {
        seedPending(null, "k1", validPost("k1"));
        MockHttpServletResponse real = new MockHttpServletResponse();
        CapturingResponse capture = new CapturingResponse(real, 1024);

        assertFalse(interceptor.preHandle(asFilterWould(validPost("k1")), capture, create()));

        assertFalse(capture.isCapturing());
        assertProblem(real, ProblemType.REQUEST_IN_PROGRESS);
    }

    @Test
    void theCapturingResponseIsFoundEvenWhenAnotherWrapperSitsAroundIt() throws Exception {
        CapturingResponse capture = new CapturingResponse(new MockHttpServletResponse(), 1024);
        HttpServletResponseWrapper outer = new HttpServletResponseWrapper(new HttpServletResponseWrapper(capture));

        assertTrue(interceptor.preHandle(asFilterWould(validPost("k1")), outer, create()));

        assertTrue(capture.isCapturing(), "another filter (compression, logging) may have wrapped the response");
    }

    @Test
    void aPlainResponseWithoutTheFilterStillLetsTheRequestRun() throws Exception {
        MockHttpServletRequest request = validPost("k1");

        assertTrue(run(request, new MockHttpServletResponse(), create()));

        assertNotNull(claimAttribute(request));
    }
}
