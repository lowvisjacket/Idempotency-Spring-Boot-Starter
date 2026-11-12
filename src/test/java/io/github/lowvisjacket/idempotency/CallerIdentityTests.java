package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.annotation.Idempotent;
import io.github.lowvisjacket.idempotency.fingerprint.Fingerprint;
import io.github.lowvisjacket.idempotency.fingerprint.RequestWrapper;
import io.github.lowvisjacket.idempotency.web.CallerScope;
import io.github.lowvisjacket.idempotency.web.IdempotencyInterceptor;
import io.github.lowvisjacket.idempotency.web.ProblemResponseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F11 to F14: who a request belongs to. Everything beyond the servlet principal used to be invisible, so users
 * authenticated by header, tenants and applications sharing one Redis, and anonymous callers all shared records.
 */
class CallerIdentityTests {

    @RestController
    static class Api {
        @Idempotent
        @PostMapping("/pay")
        String pay() {
            return "ok";
        }
    }

    private final IdempotencyInterceptorTests.FakeStore store = new IdempotencyInterceptorTests.FakeStore();

    private IdempotencyInterceptor interceptor(CallerScope scope) {
        return new IdempotencyInterceptor(store, new Fingerprint(), new ProblemResponseWriter(JsonMapper.builder().build()), scope);
    }

    private MockHttpServletRequest post(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/pay");
        request.addHeader("Idempotency-Key", "k1");
        request.setContent("{\"amount\":5}".getBytes(StandardCharsets.UTF_8));
        if (authorization != null) request.addHeader("Authorization", authorization);
        return request;
    }

    private boolean run(IdempotencyInterceptor interceptor, MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        HandlerMethod handler = new HandlerMethod(new Api(), Api.class.getDeclaredMethod("pay"));
        return interceptor.preHandle(new RequestWrapper(request), response, handler);
    }

    @Test
    void F11_twoUsersAuthenticatedByAnAuthorizationHeaderDoNotShareRecords() throws Exception {
        IdempotencyInterceptor interceptor = interceptor(new CallerScope());

        assertTrue(run(interceptor, post("Bearer token-of-alice"), new MockHttpServletResponse()));
        MockHttpServletResponse bob = new MockHttpServletResponse();
        boolean bobRuns = run(interceptor, post("Bearer token-of-bob"), bob);

        assertTrue(bobRuns, "bob must get a record of his own, not alice's: " + bob.getContentAsString());
        assertEquals(2, store.data.size());
    }

    @Test
    void F11_theSameBearerTokenStillFindsItsOwnRecord() throws Exception {
        IdempotencyInterceptor interceptor = interceptor(new CallerScope());

        assertTrue(run(interceptor, post("Bearer token-of-alice"), new MockHttpServletResponse()));
        MockHttpServletResponse retry = new MockHttpServletResponse();

        assertFalse(run(interceptor, post("Bearer token-of-alice"), retry));
        assertEquals(409, retry.getStatus());
        assertEquals(1, store.data.size());
    }

    @Test
    void F12_aConfiguredTenantHeaderSeparatesTenantsBehindOneServiceAccount() throws Exception {
        IdempotencyInterceptor interceptor = interceptor(new CallerScope(null, null, List.of("X-Tenant-Id"), false));
        MockHttpServletRequest tenantA = post(null);
        tenantA.setUserPrincipal(() -> "service-account");
        tenantA.addHeader("X-Tenant-Id", "A");
        MockHttpServletRequest tenantB = post(null);
        tenantB.setUserPrincipal(() -> "service-account");
        tenantB.addHeader("X-Tenant-Id", "B");

        assertTrue(run(interceptor, tenantA, new MockHttpServletResponse()));

        assertTrue(run(interceptor, tenantB, new MockHttpServletResponse()), "tenant B must not see tenant A's record");
    }

    @Test
    void F13_twoApplicationsSharingARedisDoNotReadEachOthersRecords() throws Exception {
        IdempotencyInterceptor orders = interceptor(new CallerScope("orders", null, List.of(), false));
        IdempotencyInterceptor billing = interceptor(new CallerScope("billing", null, List.of(), false));
        MockHttpServletRequest a = post(null);
        a.setUserPrincipal(() -> "alice");
        MockHttpServletRequest b = post(null);
        b.setUserPrincipal(() -> "alice");

        assertTrue(run(orders, a, new MockHttpServletResponse()));

        assertTrue(run(billing, b, new MockHttpServletResponse()));
        assertEquals(2, store.data.size());
    }

    @Test
    void F14_anonymousCallersCanBeRefusedInsteadOfSharingOneNamespace() throws Exception {
        IdempotencyInterceptor interceptor = interceptor(new CallerScope(null, null, List.of(), true));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(run(interceptor, post(null), response));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("caller-not-identified"));
        assertEquals(0, store.data.size());
    }

    @Test
    void F14_anIdentifiedCallerIsStillServedWhenAnonymousCallersAreRefused() throws Exception {
        IdempotencyInterceptor interceptor = interceptor(new CallerScope(null, null, List.of(), true));
        MockHttpServletRequest request = post(null);
        request.setUserPrincipal(() -> "alice");

        assertTrue(run(interceptor, request, new MockHttpServletResponse()));
    }
}
