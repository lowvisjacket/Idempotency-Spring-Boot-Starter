package io.github.lowvisjacket.idempotency.web;

import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;
import io.github.lowvisjacket.idempotency.model.Status;
import io.github.lowvisjacket.idempotency.store.IdempotencyStore;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Finishes what the interceptor started: after the controller has run, saves its response (or removes the claim)
 * and then lets the client have the response.
 *
 * <p>The filter cannot know which endpoints are {@code @Idempotent} (that is only known once Spring has chosen the
 * controller), so it wraps <em>every</em> response in a {@link CapturingResponse}. That costs nothing for a request
 * that never claimed a key, because the wrapper only holds bytes after the interceptor calls
 * {@link CapturingResponse#startCapturing()}.
 *
 * <p>What happens after {@code chain.doFilter}, in this order:
 * <ol>
 *   <li><b>No claim.</b> If the request has no {@link io.github.lowvisjacket.idempotency.config.IdempotencyDefaults#CLAIM_ATTRIBUTE}
 *       (or the attribute is not an {@code IdempotencyRecord}), this request claimed nothing: release the response
 *       and make no store calls at all.</li>
 *   <li><b>Async.</b> If {@code request.isAsyncStarted()}, the real answer comes later, so do nothing now: release
 *       the response and leave the record alone. (Async controllers are not supported yet; the record stays
 *       {@code PENDING} until its TTL ends.)</li>
 *   <li><b>Decide.</b> The record is <b>deleted</b> so the client can retry when the response should not be
 *       replayed, and <b>completed</b> otherwise:
 *     <ul>
 *       <li>status 500 or above: delete (a failure is retried, not replayed);</li>
 *       <li>the body went over {@link io.github.lowvisjacket.idempotency.config.IdempotencyDefaults#MAX_RESPONSE_SIZE_BYTES}
 *           (the response is still sent in full): delete, since it cannot be saved without cutting it;</li>
 *       <li>the body is not valid UTF-8 (the record stores text): delete;</li>
 *       <li>anything else, including 3xx and 4xx: complete. Build a <em>new</em> record with status
 *           {@code COMPLETED}, the key, fingerprint and TTL of the claimed one, the response status, and the body as
 *           UTF-8 text ({@code null} when the body is empty). The claimed record itself is not changed.</li>
 *     </ul></li>
 *   <li><b>Store failures.</b> A {@link RuntimeException} from {@code complete} or {@code delete} is logged and
 *       swallowed. The response has been produced, so the client must still get it; the record then simply stays
 *       until its TTL ends. A {@code false} from {@code complete} (the key expired mid-request) is not an error.</li>
 *   <li><b>Release.</b> Only <em>after</em> the store call (so the record is final before the client can retry),
 *       and even if that call failed, send the held response to the client.</li>
 * </ol>
 *
 * <p><b>If the controller fails</b> ({@code chain.doFilter} throws anything): if the request claimed a key, delete
 * it (a failed store call here must not hide the original exception), drop the held body without sending it, and
 * rethrow the original exception unchanged.
 *
 * <p>Stateless apart from its settings, so one instance serves all requests and threads.
 *
 * @see CapturingResponse
 * @see IdempotencyInterceptor
 */
public class ResponseCaptureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ResponseCaptureFilter.class);

    private final IdempotencyStore store;
    private final int maxResponseBytes;

    /**
     * Uses {@link io.github.lowvisjacket.idempotency.config.IdempotencyDefaults#MAX_RESPONSE_SIZE_BYTES}.
     *
     * @param store where claimed records are completed or deleted
     * @throws NullPointerException if {@code store} is null
     */
    public ResponseCaptureFilter(IdempotencyStore store) {
        this(store, IdempotencyDefaults.MAX_RESPONSE_SIZE_BYTES);
    }

    /**
     * @param store            where claimed records are completed or deleted
     * @param maxResponseBytes the most body bytes to hold per response (smaller values are handy in tests)
     * @throws NullPointerException     if {@code store} is null
     * @throws IllegalArgumentException if {@code maxResponseBytes} is negative
     */
    public ResponseCaptureFilter(IdempotencyStore store, int maxResponseBytes) {
        this.store = Objects.requireNonNull(store, "store");
        if (maxResponseBytes < 0) {
            throw new IllegalArgumentException("maxResponseBytes must not be negative");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * Runs the steps in the class description.
     *
     * @throws ServletException whatever the rest of the chain throws is rethrown as it is
     * @throws IOException      whatever the rest of the chain throws is rethrown as it is
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CapturingResponse wrapper = new CapturingResponse(response, maxResponseBytes);

        try {
            chain.doFilter(request, wrapper);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            // The controller failed: give the key back, drop the held body (the container writes its own error
            // page) and rethrow the same exception. A broken store must never replace it.
            IdempotencyRecord claim = claimOf(request);
            if (claim != null) {
                deleteQuietly(claim);
            }
            throw e;
        }

        IdempotencyRecord claim = claimOf(request);
        if (claim == null || request.isAsyncStarted()) {
            wrapper.release();
            return;
        }

        try {
            settle(claim, wrapper);
        } finally {
            // after the store call, so the record is final before the client can see the response
            wrapper.release();
        }
    }

    /** Completes the claim when the response can be replayed, deletes it otherwise. Never throws for store errors. */
    private void settle(IdempotencyRecord claim, CapturingResponse wrapper) {
        int status = wrapper.getStatus();
        boolean saveable = status < 500 && !wrapper.isOverflowed() && wrapper.isCapturing();

        String body = null;
        if (saveable) {
            byte[] bytes = wrapper.getCapturedBody();
            if (bytes.length > 0) {
                body = decodeUtf8(bytes);
                saveable = body != null;
            }
        }

        try {
            if (saveable) {
                IdempotencyRecord done = new IdempotencyRecord(
                        claim.getKey(), claim.getFingerprint(), null, body, status, claim.getTtl());
                done.setStatus(Status.COMPLETED);
                store.complete(done); // false = the key expired meanwhile; nothing more to do
            } else {
                store.delete(claim.getKey());
            }
        } catch (RuntimeException e) {
            log.warn("Could not settle idempotency record; it stays until its TTL ends", e);
        }
    }

    private void deleteQuietly(IdempotencyRecord claim) {
        try {
            store.delete(claim.getKey());
        } catch (RuntimeException e) {
            log.warn("Could not delete idempotency record after a failed request", e);
        }
    }

    /** @return the claimed record, or null if this request claimed nothing (absent or wrong type) */
    private static IdempotencyRecord claimOf(HttpServletRequest request) {
        return request.getAttribute(IdempotencyDefaults.CLAIM_ATTRIBUTE) instanceof IdempotencyRecord record
                ? record : null;
    }

    /** @return the text, or null if the bytes are not valid UTF-8 (strict: bad bytes are reported, not replaced) */
    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
