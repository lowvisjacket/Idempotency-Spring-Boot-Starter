package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.fingerprint.RequestFilter;
import io.github.lowvisjacket.idempotency.fingerprint.RequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain unit tests for RequestFilter. MockFilterChain remembers the request it was handed,
 * which is exactly what the controller would receive.
 */
class RequestFilterTests {

    private final RequestFilter filter = new RequestFilter();

    private MockHttpServletRequest request(String method, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/payments");
        if (body != null) request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private HttpServletRequest runFilter(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return (HttpServletRequest) chain.getRequest();
    }

    @Test
    void getRequestsPassThroughUntouched() throws Exception {
        MockHttpServletRequest request = request("GET", null);

        assertSame(request, runFilter(request, new MockHttpServletResponse()));
    }

    @Test
    void methodsWithoutBodiesPassThroughUntouched() throws Exception {
        for (String method : new String[]{"HEAD", "OPTIONS", "DELETE"}) {
            MockHttpServletRequest request = request(method, null);

            assertSame(request, runFilter(request, new MockHttpServletResponse()), method + " should not be wrapped");
        }
    }

    @Test
    void postIsWrappedAndTheControllerCanStillReadTheBody() throws Exception {
        MockHttpServletRequest request = request("POST", "{\"amount\":50}");

        HttpServletRequest downstream = runFilter(request, new MockHttpServletResponse());

        assertInstanceOf(RequestWrapper.class, downstream);
        assertEquals("{\"amount\":50}", new String(downstream.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void putAndPatchAreWrappedToo() throws Exception {
        for (String method : new String[]{"PUT", "PATCH"}) {
            HttpServletRequest downstream = runFilter(request(method, "x"), new MockHttpServletResponse());

            assertInstanceOf(RequestWrapper.class, downstream, method + " should be wrapped");
        }
    }

    @Test
    void anOversizedPostIsPassedThroughUntouchedAndFlagged() throws Exception {
        MockHttpServletRequest request = request("POST", "x".repeat(IdempotencyDefaults.MAX_BODY_SIZE_BYTES + 1));

        HttpServletRequest downstream = runFilter(request, new MockHttpServletResponse());

        assertSame(request, downstream, "an oversized body must not be buffered or wrapped");
        assertEquals(true, downstream.getAttribute(IdempotencyDefaults.BODY_EXCEEDS_CAP_ATTRIBUTE));
    }

    /** A POST whose length is not declared (as with Transfer-Encoding: chunked). */
    private static MockHttpServletRequest chunked(byte[] content) {
        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/upload") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        chunked.setContent(content);
        return chunked;
    }

    @Test
    void anOversizedBodyWithNoDeclaredLengthIsFlaggedAndStillReachesTheControllerInFull() throws Exception {
        byte[] content = "x".repeat(IdempotencyDefaults.MAX_BODY_SIZE_BYTES * 5 + 7).getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest chunked = chunked(content);
        MockHttpServletResponse response = new MockHttpServletResponse();

        HttpServletRequest downstream = runFilter(chunked, response);

        assertNotEquals(413, response.getStatus(), "a plain endpoint must not be rejected by the starter");
        assertNotNull(downstream, "the rest of the app must run");
        assertEquals(true, downstream.getAttribute(IdempotencyDefaults.BODY_EXCEEDS_CAP_ATTRIBUTE));
        assertArrayEquals(content, downstream.getInputStream().readAllBytes());
    }

    @Test
    void aBodyWithNoDeclaredLengthIsNeverBufferedBeyondTheCap() throws Exception {
        // F01: the filter used to call readAllBytes(), holding an entire chunked upload in the heap.
        long total = 50L * 1024 * 1024;
        long[] produced = {0};
        jakarta.servlet.ServletInputStream endless = new jakarta.servlet.ServletInputStream() {
            @Override
            public int read() {
                return produced[0] < total ? (int) (produced[0]++ & 0x7f) : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (produced[0] >= total) return -1;
                int n = (int) Math.min(len, total - produced[0]);
                produced[0] += n;
                return n;
            }

            @Override
            public boolean isFinished() {
                return produced[0] >= total;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(jakarta.servlet.ReadListener listener) {
            }
        };
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/upload") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public jakarta.servlet.ServletInputStream getInputStream() {
                return endless;
            }
        };

        HttpServletRequest downstream = runFilter(request, new MockHttpServletResponse());

        assertTrue(produced[0] <= IdempotencyDefaults.MAX_BODY_SIZE_BYTES + 8192,
                "the filter read " + produced[0] + " bytes before the controller asked for any");
        assertEquals(true, downstream.getAttribute(IdempotencyDefaults.BODY_EXCEEDS_CAP_ATTRIBUTE));
    }

    @Test
    void aMethodNotBufferedByTheFilterKeepsItsBodyWhenTheInterceptorFingerprintsIt() throws Exception {
        MockHttpServletRequest delete = request("DELETE", "{\"reason\":\"x\"}");

        RequestWrapper wrapper = RequestWrapper.withoutBody(delete);

        assertEquals(0, wrapper.getSavedBody().length);
        assertEquals("{\"reason\":\"x\"}", new String(delete.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void aPostAtTheLimitIsWrappedAndNotFlagged() throws Exception {
        MockHttpServletRequest request = request("POST", "x".repeat(IdempotencyDefaults.MAX_BODY_SIZE_BYTES));

        HttpServletRequest downstream = runFilter(request, new MockHttpServletResponse());

        assertInstanceOf(RequestWrapper.class, downstream);
        assertNull(downstream.getAttribute(IdempotencyDefaults.BODY_EXCEEDS_CAP_ATTRIBUTE));
    }

    @Test
    void anErrorFromTheRestOfTheAppIsNotRewrittenByTheFilter() {
        MockHttpServletRequest request = request("POST", "x");
        MockFilterChain failingChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                throw new IllegalStateException("controller blew up");
            }
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> filter.doFilter(request, new MockHttpServletResponse(), failingChain));

        assertEquals("controller blew up", thrown.getMessage());
    }
}
