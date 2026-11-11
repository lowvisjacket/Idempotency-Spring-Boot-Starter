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

    @Test
    void anOversizedBodyWithNoDeclaredLengthGetsA413AndTheChainIsNotCalled() throws Exception {
        // A chunked upload has no Content-Length, so the filter only finds out the body is too big while reading it.
        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/payments") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        chunked.setContent("x".repeat(IdempotencyDefaults.MAX_BODY_SIZE_BYTES + 1).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(chunked, response, chain);

        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest(), "the rest of the app must not run");
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
