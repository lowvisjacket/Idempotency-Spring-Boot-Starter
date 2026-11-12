package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.fingerprint.Fingerprint;
import io.github.lowvisjacket.idempotency.fingerprint.RequestWrapper;
import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;
import io.github.lowvisjacket.idempotency.model.Status;
import io.github.lowvisjacket.idempotency.store.StoredKeys;
import io.github.lowvisjacket.idempotency.web.CapturingResponse;
import io.github.lowvisjacket.idempotency.web.IdempotencyInterceptor;
import io.github.lowvisjacket.idempotency.web.ProblemResponseWriter;
import io.github.lowvisjacket.idempotency.web.ResponseCaptureFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Response headers that are saved with a record and replayed with it. Four parts:
 * <ol>
 *   <li><b>The record</b> carries a {@code headers} map that is never null and survives the JSON round trip.</li>
 *   <li><b>Saving</b> (the response filter): only the names in {@link IdempotencyDefaults#REPLAYED_RESPONSE_HEADERS}.</li>
 *   <li><b>Replaying</b> (the interceptor): only those names again, even if the store holds more, and never anything
 *       that could split the response.</li>
 *   <li><b>Both together</b>: what the first response had is what the replay has.</li>
 * </ol>
 * Why an allow-list: {@code Set-Cookie} and friends are private to one response, and {@code Date} or
 * {@code Content-Length} are wrong for a later one. Only headers that describe the <em>result</em> are kept.
 */
class ReplayedHeadersTests {

    private static final String KEY = "k1";
    private static final String BODY = "{\"amount\":100}";
    private static final String STORED_KEY = StoredKeys.build(null, KEY);

    private final IdempotencyInterceptorTests.FakeStore store = new IdempotencyInterceptorTests.FakeStore();
    private final Fingerprint fingerprint = new Fingerprint();
    private final ResponseCaptureFilter filter = new ResponseCaptureFilter(store);
    private final IdempotencyInterceptor interceptor =
            new IdempotencyInterceptor(store, fingerprint, new ProblemResponseWriter(JsonMapper.builder().build()));

    private static MockHttpServletRequest post(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.addHeader("Idempotency-Key", KEY);
        return request;
    }

    private static Map<String, List<String>> headers(String... nameValuePairs) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            map.computeIfAbsent(nameValuePairs[i], n -> new ArrayList<>()).add(nameValuePairs[i + 1]);
        }
        return map;
    }

    private IdempotencyRecord claimFor(MockHttpServletRequest request) throws IOException {
        return new IdempotencyRecord(STORED_KEY, fingerprint.hash(new RequestWrapper(request)), null, null, 0,
                Duration.ofHours(1));
    }

    private static void interceptorWins(ServletRequest request, ServletResponse response, IdempotencyRecord claim) {
        request.setAttribute(IdempotencyDefaults.CLAIM_ATTRIBUTE, claim);
        CapturingResponse capture = WebUtils.getNativeResponse(response, CapturingResponse.class);
        assertNotNull(capture);
        capture.startCapturing();
    }

    private IdempotencyRecord stored() {
        return store.data.get(STORED_KEY);
    }

    /** Runs a claimed request whose controller does what {@code controller} says; returns what the client got. */
    private MockHttpServletResponse serve(int status, String body, Consumer<HttpServletResponse> controller)
            throws Exception {
        IdempotencyRecord claim = claimFor(post(BODY));
        assertTrue(store.claim(claim));
        store.resetCounters();
        MockHttpServletResponse real = new MockHttpServletResponse();
        FilterChain chain = (request, response) -> {
            interceptorWins(request, response, claim);
            HttpServletResponse http = (HttpServletResponse) response;
            http.setStatus(status);
            controller.accept(http);
            if (body != null) response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        };
        filter.doFilter(new MockHttpServletRequest("POST", "/orders"), real, chain);
        return real;
    }

    private MockHttpServletResponse serve(Consumer<HttpServletResponse> controller) throws Exception {
        return serve(201, "created", controller);
    }

    private void seedCompleted(int status, String contentType, Map<String, List<String>> headers, String body)
            throws IOException {
        IdempotencyRecord record = new IdempotencyRecord(STORED_KEY, claimFor(post(BODY)).getFingerprint(),
                contentType, headers, body, status, Duration.ofHours(1));
        record.setStatus(Status.COMPLETED);
        assertTrue(store.claim(record));
        store.resetCounters();
    }

    private MockHttpServletResponse replay(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(new RequestWrapper(request), response,
                new HandlerMethod(new IdempotencyInterceptorTests.Controller(), "create"));
        return response;
    }

    private MockHttpServletResponse replay() throws Exception {
        return replay(post(BODY));
    }

    @Test
    void theListOfReplayedHeadersIsTheDocumentedOne() {
        assertEquals(List.of("Location", "Content-Location", "ETag", "Content-Language",
                        "Cache-Control", "Expires", "Pragma", "Vary"),
                IdempotencyDefaults.REPLAYED_RESPONSE_HEADERS);
    }

    @Test
    void theListNeverContainsAnythingPrivateOrPerResponse() {
        for (String name : new String[]{"Set-Cookie", "Authorization", "Date", "Content-Length", "Content-Type",
                "Transfer-Encoding", "Idempotency-Replayed", "Retry-After", "Connection"}) {
            assertFalse(IdempotencyDefaults.REPLAYED_RESPONSE_HEADERS.stream().anyMatch(name::equalsIgnoreCase),
                    name + " must never be replayed");
        }
    }

    @Test
    void aRecordHasNoHeadersByDefaultAndTheMapIsNotNull() {
        assertNotNull(new IdempotencyRecord().getHeaders());
        assertTrue(new IdempotencyRecord().getHeaders().isEmpty());
        assertNotNull(new IdempotencyRecord("k", "f", null, "b", 200, Duration.ofSeconds(1)).getHeaders());
    }

    @Test
    void theLongConstructorKeepsTheHeaders() {
        IdempotencyRecord r = new IdempotencyRecord("k", "f", "text/plain", headers("Location", "/a"), "b", 201,
                Duration.ofSeconds(1));
        assertEquals(headers("Location", "/a"), r.getHeaders());
        assertEquals("text/plain", r.getContentType());
        assertEquals("b", r.getBody());
        assertEquals(201, r.getHttpStatus());
    }

    @Test
    void nullHeadersBecomeAnEmptyMap() {
        IdempotencyRecord r = new IdempotencyRecord("k", "f", null, null, "b", 200, Duration.ofSeconds(1));
        assertEquals(Map.of(), r.getHeaders());
        r.setHeaders(headers("ETag", "\"v1\""));
        r.setHeaders(null);
        assertEquals(Map.of(), r.getHeaders());
    }

    @Test
    void theMapIsCopiedSoLaterChangesToTheSourceDoNotLeakIn() {
        Map<String, List<String>> source = headers("Location", "/a");
        IdempotencyRecord r = new IdempotencyRecord("k", "f", null, source, "b", 201, Duration.ofSeconds(1));
        source.get("Location").add("/sneaky");
        source.put("ETag", new ArrayList<>(List.of("x")));
        assertEquals(headers("Location", "/a"), r.getHeaders());
    }

    @Test
    void aNullListOfValuesBecomesAnEmptyList() {
        Map<String, List<String>> source = new LinkedHashMap<>();
        source.put("Location", null);
        IdempotencyRecord r = new IdempotencyRecord("k", "f", null, source, "b", 201, Duration.ofSeconds(1));
        assertEquals(List.of(), r.getHeaders().get("Location"));
    }

    @Test
    void headersSurviveTheJsonRoundTripInOrder() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        IdempotencyRecord r = new IdempotencyRecord("k", "f", "application/json", headers(
                "Location", "/orders/7", "Content-Language", "en", "Content-Language", "fr"), "{}", 201,
                Duration.ofSeconds(1));
        r.setStatus(Status.COMPLETED);

        IdempotencyRecord back = mapper.readValue(mapper.writeValueAsString(r), IdempotencyRecord.class);

        assertEquals(r.getHeaders(), back.getHeaders());
        assertEquals(List.of("en", "fr"), back.getHeaders().get("Content-Language"));
        assertEquals("application/json", back.getContentType());
    }

    @Test
    void aRecordSavedBeforeHeadersExistedStillLoadsWithNoHeaders() throws Exception {
        String old = "{\"fingerprint\":\"f\",\"status\":\"COMPLETED\",\"contentType\":\"text/plain\","
                + "\"body\":\"x\",\"httpStatus\":200}";
        IdempotencyRecord r = JsonMapper.builder().build().readValue(old, IdempotencyRecord.class);
        assertEquals(Map.of(), r.getHeaders());
        assertEquals("x", r.getBody());
    }

    @Test
    void aJsonNullForHeadersLoadsAsEmpty() throws Exception {
        String json = "{\"fingerprint\":\"f\",\"status\":\"PENDING\",\"headers\":null,\"httpStatus\":0}";
        assertEquals(Map.of(), JsonMapper.builder().build().readValue(json, IdempotencyRecord.class).getHeaders());
    }

    @Test
    void locationIsSaved() throws Exception {
        serve(r -> r.setHeader("Location", "/orders/7"));
        assertEquals(headers("Location", "/orders/7"), stored().getHeaders());
    }

    @ParameterizedTest(name = "{0} is saved")
    @ValueSource(strings = {"Location", "Content-Location", "ETag", "Content-Language", "Cache-Control", "Expires", "Pragma", "Vary"})
    void everyAllowedHeaderIsSaved(String name) throws Exception {
        serve(r -> r.setHeader(name, "value-1"));
        assertEquals(headers(name, "value-1"), stored().getHeaders());
    }

    @Test
    void aNoStoreResponseStillSaysSoWhenItIsReplayed() throws Exception {
        serve(r -> {
            r.setHeader("Cache-Control", "no-store");
            r.setHeader("Vary", "Authorization");
        });

        MockHttpServletResponse replayed = replay();

        assertEquals("no-store", replayed.getHeader("Cache-Control"));
        assertEquals("Authorization", replayed.getHeader("Vary"));
    }

    @Test
    void theNameIsStoredWithTheSpellingFromTheListWhateverTheControllerWrote() throws Exception {
        serve(r -> {
            r.setHeader("location", "/a");
            r.setHeader("ETAG", "\"v1\"");
            r.setHeader("content-language", "en");
        });
        assertEquals(List.of("Location", "ETag", "Content-Language"), new ArrayList<>(stored().getHeaders().keySet()));
    }

    @Test
    void everyValueOfARepeatedHeaderIsSavedInOrder() throws Exception {
        serve(r -> {
            r.addHeader("Content-Language", "en");
            r.addHeader("Content-Language", "fr");
            r.addHeader("Content-Language", "de");
        });
        assertEquals(List.of("en", "fr", "de"), stored().getHeaders().get("Content-Language"));
    }

    @Test
    void aHeaderThatWasReplacedKeepsOnlyItsLastValue() throws Exception {
        serve(r -> {
            r.setHeader("Location", "/first");
            r.setHeader("Location", "/second");
        });
        assertEquals(List.of("/second"), stored().getHeaders().get("Location"));
    }

    @Test
    void namesAreInTheOrderOfTheListNotTheOrderTheControllerSetThem() throws Exception {
        serve(r -> {
            r.setHeader("Content-Language", "en");
            r.setHeader("ETag", "\"v1\"");
            r.setHeader("Location", "/a");
        });
        assertEquals(List.of("Location", "ETag", "Content-Language"), new ArrayList<>(stored().getHeaders().keySet()));
    }

    @Test
    void headersThatAreNotOnTheListAreNeverSaved() throws Exception {
        serve(r -> {
            r.setHeader("Location", "/a");
            r.setHeader("Set-Cookie", "session=secret");
            r.setHeader("Authorization", "Bearer abc");
            r.setHeader("X-Request-Id", "req-9");
            r.setHeader("Date", "Tue, 15 Nov 1994 08:12:31 GMT");
            r.setHeader("Retry-After", "5");
            r.setHeader("Idempotency-Replayed", "true");
            r.setHeader("Transfer-Encoding", "chunked");
        });
        assertEquals(List.of("Location"), new ArrayList<>(stored().getHeaders().keySet()));
    }

    @Test
    void aResponseWithoutAllowedHeadersSavesAnEmptyMapNotNull() throws Exception {
        serve(r -> r.setHeader("Set-Cookie", "session=secret"));
        assertNotNull(stored().getHeaders());
        assertTrue(stored().getHeaders().isEmpty());
        assertEquals(Status.COMPLETED, stored().getStatus());
    }

    @Test
    void contentTypeStaysInItsOwnFieldAndNotInTheMap() throws Exception {
        serve(r -> {
            r.setContentType("application/json");
            r.setHeader("Location", "/a");
        });
        assertEquals("application/json", stored().getContentType());
        assertEquals(List.of("Location"), new ArrayList<>(stored().getHeaders().keySet()));
    }

    @Test
    void valuesAreSavedExactlyAsSent() throws Exception {
        String tricky = "/orders/7?a=b&c=%C3%A9#frag;x=\"y\"";
        serve(r -> r.setHeader("Location", tricky));
        assertEquals(List.of(tricky), stored().getHeaders().get("Location"));
    }

    @Test
    void headersSetAfterTheBodyWasWrittenAreStillSaved() throws Exception {
        IdempotencyRecord claim = claimFor(post(BODY));
        assertTrue(store.claim(claim));
        filter.doFilter(new MockHttpServletRequest("POST", "/orders"), new MockHttpServletResponse(), (request, response) -> {
            interceptorWins(request, response, claim);
            ((HttpServletResponse) response).setStatus(201);
            response.getOutputStream().write("body".getBytes(StandardCharsets.UTF_8));
            ((HttpServletResponse) response).setHeader("Location", "/late");
        });
        assertEquals(List.of("/late"), stored().getHeaders().get("Location"));
    }

    @Test
    void anEmptyBodyStillSavesTheHeaders() throws Exception {
        serve(204, null, r -> r.setHeader("Location", "/a"));
        assertEquals(204, stored().getHttpStatus());
        assertEquals(List.of("/a"), stored().getHeaders().get("Location"));
    }

    @ParameterizedTest(name = "status {0} keeps its headers")
    @ValueSource(ints = {200, 201, 302, 303, 400, 404, 409, 422})
    void headersAreSavedForEveryStatusThatIsSaved(int status) throws Exception {
        serve(status, "x", r -> r.setHeader("Location", "/a"));
        assertEquals(Status.COMPLETED, stored().getStatus());
        assertEquals(List.of("/a"), stored().getHeaders().get("Location"));
    }

    @Test
    void aFailedResponseIsDeletedAndHeadersAreNotSavedAnywhere() throws Exception {
        serve(500, "boom", r -> r.setHeader("Location", "/a"));
        assertNull(stored());
        assertEquals(1, store.deletes.get());
        assertEquals(0, store.completes.get());
    }

    @Test
    void theClaimedRecordItselfIsNotChanged() throws Exception {
        IdempotencyRecord claim = claimFor(post(BODY));
        assertTrue(store.claim(claim));
        filter.doFilter(new MockHttpServletRequest("POST", "/orders"), new MockHttpServletResponse(), (request, response) -> {
            interceptorWins(request, response, claim);
            ((HttpServletResponse) response).setHeader("Location", "/a");
        });
        assertTrue(claim.getHeaders().isEmpty());
        assertEquals(Status.PENDING, claim.getStatus());
    }

    @Test
    void theClientStillGetsEveryHeaderOnTheFirstResponseIncludingTheOnesNotSaved() throws Exception {
        MockHttpServletResponse real = serve(r -> {
            r.setHeader("Location", "/orders/7");
            r.addHeader("Content-Language", "en");
            r.addHeader("Content-Language", "fr");
            r.setHeader("Set-Cookie", "session=secret");
        });
        assertEquals("/orders/7", real.getHeader("Location"));
        assertEquals(List.of("en", "fr"), real.getHeaders("Content-Language"));
        assertEquals("session=secret", real.getHeader("Set-Cookie"));
        assertEquals("created", real.getContentAsString());
        assertEquals(201, real.getStatus());
    }

    @Test
    void theOtherFieldsOfTheSavedRecordAreUnchangedByHeaders() throws Exception {
        serve(r -> {
            r.setContentType("text/plain;charset=UTF-8");
            r.setHeader("Location", "/a");
        });
        IdempotencyRecord saved = stored();
        assertEquals(Status.COMPLETED, saved.getStatus());
        assertEquals("created", saved.getBody());
        assertEquals(201, saved.getHttpStatus());
        assertEquals("text/plain;charset=UTF-8", saved.getContentType());
        assertEquals(claimFor(post(BODY)).getFingerprint(), saved.getFingerprint());
    }

    @Test
    void aReplayCarriesTheSavedLocation() throws Exception {
        seedCompleted(201, null, headers("Location", "/orders/7"), "created");
        MockHttpServletResponse response = replay();
        assertEquals("/orders/7", response.getHeader("Location"));
        assertEquals(201, response.getStatus());
        assertEquals("created", response.getContentAsString());
    }

    @ParameterizedTest(name = "{0} is replayed")
    @ValueSource(strings = {"Location", "Content-Location", "ETag", "Content-Language"})
    void everyAllowedHeaderIsReplayed(String name) throws Exception {
        seedCompleted(200, null, headers(name, "value-1"), "x");
        assertEquals("value-1", replay().getHeader(name));
    }

    @Test
    void everyValueOfARepeatedHeaderIsReplayedInOrder() throws Exception {
        seedCompleted(200, null, headers("Content-Language", "en", "Content-Language", "fr"), "x");
        assertEquals(List.of("en", "fr"), replay().getHeaders("Content-Language"));
    }

    @Test
    void aRecordWithoutHeadersReplaysExactlyAsBefore() throws Exception {
        seedCompleted(201, "text/plain", null, "created");
        MockHttpServletResponse response = replay();
        assertEquals(201, response.getStatus());
        assertEquals("created", response.getContentAsString());
        assertEquals("true", response.getHeader(IdempotencyDefaults.REPLAY_HEADER));
        assertNull(response.getHeader("Location"));
        assertEquals(java.util.Set.of(IdempotencyDefaults.REPLAY_HEADER, "Content-Type"),
                new java.util.HashSet<>(response.getHeaderNames()),
                "only the marker and the Content-Type may be on this response");
    }

    @Test
    void anEmptyBodyReplayStillCarriesTheHeaders() throws Exception {
        seedCompleted(204, null, headers("Location", "/a"), null);
        MockHttpServletResponse response = replay();
        assertEquals(204, response.getStatus());
        assertEquals("/a", response.getHeader("Location"));
    }

    @Test
    void aHeaderTheStoreHoldsButTheListDoesNotAllowIsNotReplayed() throws Exception {
        seedCompleted(200, null, headers(
                "Set-Cookie", "session=stolen",
                "Authorization", "Bearer abc",
                "X-Evil", "1",
                "Date", "yesterday",
                "Location", "/ok"), "x");
        MockHttpServletResponse response = replay();
        assertEquals("/ok", response.getHeader("Location"));
        assertNull(response.getHeader("Set-Cookie"));
        assertNull(response.getHeader("Authorization"));
        assertNull(response.getHeader("X-Evil"));
        assertNull(response.getHeader("Date"));
    }

    @Test
    void namesInTheStoreMatchIgnoringCaseAndAreSentWithTheListsSpelling() throws Exception {
        seedCompleted(200, null, headers("location", "/a", "ETAG", "\"v1\""), "x");
        MockHttpServletResponse response = replay();
        assertTrue(response.getHeaderNames().contains("Location"), response.getHeaderNames().toString());
        assertTrue(response.getHeaderNames().contains("ETag"), response.getHeaderNames().toString());
        assertEquals("/a", response.getHeader("Location"));
    }

    @ParameterizedTest(name = "value {0} is skipped")
    @ValueSource(strings = {"/x\r\nSet-Cookie: a=b", "/x\nSet-Cookie: a=b", "/x\rSet-Cookie: a=b", "\r\n"})
    void aValueThatCouldSplitTheResponseIsSkipped(String evil) throws Exception {
        seedCompleted(200, null, headers("Location", evil), "x");
        MockHttpServletResponse response = replay();
        assertNull(response.getHeader("Location"));
        assertNull(response.getHeader("Set-Cookie"));
        assertEquals("x", response.getContentAsString());
    }

    @Test
    void oneBadValueDoesNotStopTheGoodOnesAroundIt() throws Exception {
        seedCompleted(200, null, headers(
                "Content-Language", "en", "Content-Language", "x\r\ny", "Content-Language", "fr"), "x");
        assertEquals(List.of("en", "fr"), replay().getHeaders("Content-Language"));
    }

    @Test
    void aNullValueInsideTheStoredListIsSkippedWithoutFailing() throws Exception {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("Content-Language", new ArrayList<>(Arrays.asList("en", null, "fr")));
        seedCompleted(200, null, map, "x");
        assertEquals(List.of("en", "fr"), replay().getHeaders("Content-Language"));
    }

    @Test
    void theReplayMarkerCannotBeOverwrittenByTheStore() throws Exception {
        seedCompleted(200, null, headers(IdempotencyDefaults.REPLAY_HEADER, "false", "Location", "/a"), "x");
        MockHttpServletResponse response = replay();
        assertEquals(List.of("true"), response.getHeaders(IdempotencyDefaults.REPLAY_HEADER));
    }

    @Test
    void theSavedContentTypeWinsOverAContentTypeInTheMap() throws Exception {
        seedCompleted(200, "application/json", headers("Content-Type", "text/evil", "Location", "/a"), "{}");
        MockHttpServletResponse response = replay();
        assertEquals("application/json", response.getContentType());
        assertNull(response.getHeader("X-Anything"));
    }

    @Test
    void replayingTwiceGivesTheSameHeadersAndDoesNotChangeTheStoredRecord() throws Exception {
        seedCompleted(201, null, headers("Location", "/a", "Content-Language", "en", "Content-Language", "fr"), "x");
        MockHttpServletResponse first = replay();
        MockHttpServletResponse second = replay();
        assertEquals(first.getHeaders("Location"), second.getHeaders("Location"));
        assertEquals(List.of("en", "fr"), second.getHeaders("Content-Language"));
        assertEquals(headers("Location", "/a", "Content-Language", "en", "Content-Language", "fr"),
                stored().getHeaders());
    }

    @Test
    void aRefusalDoesNotCarryTheSavedHeaders() throws Exception {
        seedCompleted(201, null, headers("Location", "/orders/7", "ETag", "\"v1\""), "created");
        MockHttpServletResponse response = replay(post("{\"amount\":999}"));
        assertEquals(422, response.getStatus());
        assertNull(response.getHeader("Location"));
        assertNull(response.getHeader("ETag"));
    }

    @Test
    void aRequestStillRunningHasNothingToReplay() throws Exception {
        IdempotencyRecord pending = claimFor(post(BODY));
        assertTrue(store.claim(pending));
        MockHttpServletResponse response = replay();
        assertEquals(409, response.getStatus());
        assertNull(response.getHeader("Location"));
    }

    @Test
    void whatTheFirstResponseHadIsWhatTheReplayHas() throws Exception {
        MockHttpServletResponse first = serve(r -> {
            r.setContentType("application/json");
            r.setHeader("Location", "/orders/7?x=%C3%A9");
            r.setHeader("ETag", "\"v1\"");
            r.addHeader("Content-Language", "en");
            r.addHeader("Content-Language", "fr");
            r.setHeader("Set-Cookie", "session=secret");
        });

        MockHttpServletResponse replayed = replay();

        assertEquals(first.getStatus(), replayed.getStatus());
        assertEquals(first.getContentAsString(), replayed.getContentAsString());
        assertEquals(first.getHeader("Location"), replayed.getHeader("Location"));
        assertEquals(first.getHeader("ETag"), replayed.getHeader("ETag"));
        assertEquals(first.getHeaders("Content-Language"), replayed.getHeaders("Content-Language"));
        assertNull(replayed.getHeader("Set-Cookie"), "a cookie belongs to the first response only");
        assertEquals("true", replayed.getHeader(IdempotencyDefaults.REPLAY_HEADER));
    }
}
