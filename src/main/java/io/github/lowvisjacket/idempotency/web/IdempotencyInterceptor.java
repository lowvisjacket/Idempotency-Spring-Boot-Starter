package io.github.lowvisjacket.idempotency.web;

import io.github.lowvisjacket.idempotency.annotation.Idempotent;
import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.config.IdempotencyKeyValidator;
import io.github.lowvisjacket.idempotency.config.TtlParser;
import io.github.lowvisjacket.idempotency.exception.FingerprintException;
import io.github.lowvisjacket.idempotency.exception.InvalidIdempotencyKeyException;
import io.github.lowvisjacket.idempotency.fingerprint.Fingerprint;
import io.github.lowvisjacket.idempotency.fingerprint.RequestWrapper;
import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;
import io.github.lowvisjacket.idempotency.model.Status;
import io.github.lowvisjacket.idempotency.store.IdempotencyStore;
import io.github.lowvisjacket.idempotency.store.StoredKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides, before a controller runs, whether a request on an {@code @Idempotent} endpoint may run, must wait,
 * must be refused, or can be answered from a saved response.
 *
 * <p>Endpoints without {@code @Idempotent} (on the method, else on the class) are never touched: the request goes
 * on, and the store is not called. For an {@code @Idempotent} endpoint the steps are, in this order. The first
 * step that applies decides the outcome, and every refusal is an {@code application/problem+json} body written with
 * {@link ProblemResponseWriter}:
 * <ol>
 *   <li><b>Key.</b> Read the header named by {@code @Idempotent.header()}. Zero values, more than one value, or a
 *       value that {@link io.github.lowvisjacket.idempotency.config.IdempotencyKeyValidator} rejects gives
 *       {@code 400} ({@link ProblemType#INVALID_KEY}). The detail never repeats the key.</li>
 *   <li><b>Oversized body.</b> If the filter set {@link io.github.lowvisjacket.idempotency.config.IdempotencyDefaults#BODY_EXCEEDS_CAP_ATTRIBUTE}
 *       to {@code Boolean.TRUE}, the body could not be fingerprinted: {@code 413} ({@link ProblemType#BODY_TOO_LARGE}).</li>
 *   <li><b>TTL.</b> Parse {@code @Idempotent.ttl()} with {@link io.github.lowvisjacket.idempotency.config.TtlParser}
 *       <em>before</em> touching the store, so a bad annotation can never leave a record behind.</li>
 *   <li><b>Identity.</b> Build the stored key with
 *       {@link io.github.lowvisjacket.idempotency.store.StoredKeys#build} from {@code request.getUserPrincipal()}
 *       (null principal means anonymous) and the key.</li>
 *   <li><b>Fingerprint.</b> Hash the request with {@link Fingerprint}. If the request is not already a
 *       {@link io.github.lowvisjacket.idempotency.fingerprint.RequestWrapper} (the filter only wraps POST, PUT and
 *       PATCH), wrap it here; those methods carry no body, so nothing is lost for the controller.</li>
 *   <li><b>Claim.</b> Try {@link IdempotencyStore#claim} with a {@code PENDING} record (fingerprint, TTL from the
 *       annotation, no response yet). If it wins (first try or the retry below), hand the record over and
 *       return {@code true} so the controller runs: set the claimed record as the request attribute
 *       {@link io.github.lowvisjacket.idempotency.config.IdempotencyDefaults#CLAIM_ATTRIBUTE}, and call
 *       {@link CapturingResponse#startCapturing()} on the response (found with
 *       {@code WebUtils.getNativeResponse}, because another filter may have wrapped it). If there is no
 *       {@code CapturingResponse}, the request still runs, but the record can never be completed. Refusals and
 *       replays set nothing: they own no record, and the filter must not touch the real owner's.</li>
 *   <li><b>Lost the claim.</b> Look the record up with {@link IdempotencyStore#find}:
 *     <ul>
 *       <li>missing (it expired or was deleted between the two calls): claim again, <b>once</b>. If that is lost
 *           too, answer {@code 409}. Never loop.</li>
 *       <li>{@code PENDING}: {@code 409} ({@link ProblemType#REQUEST_IN_PROGRESS}) with
 *           {@code Retry-After: } {@link io.github.lowvisjacket.idempotency.config.IdempotencyDefaults#PENDING_RETRY_AFTER},
 *           whatever the fingerprint is.</li>
 *       <li>{@code COMPLETED} with a different fingerprint: {@code 422} ({@link ProblemType#KEY_REUSED}). The saved
 *           response is never shown to the second request.</li>
 *       <li>{@code COMPLETED} with the same fingerprint: replay the saved status and body, add
 *           {@link io.github.lowvisjacket.idempotency.config.IdempotencyDefaults#REPLAY_HEADER}{@code : true}.</li>
 *     </ul></li>
 * </ol>
 *
 * <p><b>Store failures.</b> Any {@link RuntimeException} from the store while claiming or finding fails closed:
 * {@code 503} ({@link ProblemType#STORE_UNAVAILABLE}) with {@code Retry-After:}
 * {@link io.github.lowvisjacket.idempotency.config.IdempotencyDefaults#STORE_RETRY_AFTER}. The exception message is
 * never copied to the client (it can contain host names or addresses), and the controller does not run. The circuit
 * breaker is a decorator around the store, so it needs no code here.
 *
 * <p>{@code preHandle} returns {@code true} only when the controller should run. For every refusal and replay the
 * response is already written and it returns {@code false}.
 *
 * <p>Instances hold no per-request state and are safe to share between threads. Saving the controller's response
 * ({@code complete}) and removing the record after a 5xx are not done here; they need the response wrapper and are
 * a later step.
 *
 * @see ProblemResponseWriter
 * @see IdempotencyStore
 */
public class IdempotencyInterceptor implements HandlerInterceptor {

    private final IdempotencyStore store;
    private final Fingerprint fingerprint;
    private final ProblemResponseWriter writer;

    /**
     * @param store       where records are claimed, read and kept
     * @param fingerprint computes the request fingerprint
     * @param writer      writes the error responses
     * @throws NullPointerException if any argument is null
     */
    public IdempotencyInterceptor(IdempotencyStore store, Fingerprint fingerprint, ProblemResponseWriter writer) {
        this.store = Objects.requireNonNull(store, "store");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /**
     * Runs the steps in the class description.
     *
     * @param request  the current request (the filter's wrapper when it was wrapped)
     * @param response the response to write refusals and replays to
     * @param handler  the chosen handler; anything that is not a {@code HandlerMethod} is ignored
     * @return true if the controller should run, false if the response has already been written
     * @throws IllegalArgumentException if {@code @Idempotent.ttl()} is not a valid TTL (a programming error in the
     *                                  application, raised before the store is used)
     * @throws Exception                as allowed by {@link HandlerInterceptor#preHandle}
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod method)) return true;

        Idempotent idempotent = findIdempotent(method);
        if (idempotent == null) return true;

        String key;
        try {
            key = readKey(request, idempotent.header());
        } catch (InvalidIdempotencyKeyException e) {
            writer.write(response, ProblemType.INVALID_KEY, e.getMessage());
            return false;
        }

        if (Boolean.TRUE.equals(request.getAttribute(IdempotencyDefaults.BODY_EXCEEDS_CAP_ATTRIBUTE))) {
            return refuseBodyTooLarge(response);
        }

        // Parsed before the store is used, so a bad annotation can never leave a record behind.
        Duration ttl = TtlParser.parse(idempotent.ttl());

        Principal principal = request.getUserPrincipal();
        String storedKey = StoredKeys.build(principal == null ? null : principal.getName(), key);

        String hash;
        try {
            RequestWrapper wrapper = request instanceof RequestWrapper already ? already : new RequestWrapper(request);
            hash = fingerprint.hash(wrapper);
        } catch (FingerprintException e) {
            return refuseBodyTooLarge(response);
        }

        /**
         * HINT (new): winning a claim now has two extra duties, in BOTH places where the claim can be won (the
         * first try, and the retry inside `found.isEmpty()`). Put them in one small private method and call it
         * from both, so they cannot drift apart:
         *   1. request.setAttribute(IdempotencyDefaults.CLAIM_ATTRIBUTE, claimRecord);
         *      (the response filter reads this to know which record to complete or delete)
         *   2. find the CapturingResponse and call startCapturing() on it. Do not test `response instanceof
         *      CapturingResponse`: another filter (compression, logging) may have wrapped our wrapper, and then
         *      the check fails. WebUtils.getNativeResponse(response, CapturingResponse.class) looks through the
         *      wrappers; it returns null when the filter is not installed, in which case simply skip this step.
         * Refusals and replays do neither of these. They own no record.
         */
        // Only the store calls are inside the try, so a bug elsewhere is not reported as "store unavailable".
        IdempotencyRecord claimRecord = new IdempotencyRecord(storedKey, hash, null, null, 0, ttl);
        IdempotencyRecord existing;
        try {
            if (store.claim(claimRecord)) {
                handOver(request, response, claimRecord);
                return true;
            }

            Optional<IdempotencyRecord> found = store.find(storedKey);
            if (found.isEmpty()) {
                // It expired or was deleted between the two calls: try once more, never loop.
                if (store.claim(claimRecord)) {
                    handOver(request, response, claimRecord);
                    return true;
                }
                return refuseInProgress(response);
            }
            existing = found.get();
        } catch (RuntimeException e) {
            // The message is deliberately not sent: it can contain host names, ports or credentials.
            writer.write(response, ProblemType.STORE_UNAVAILABLE, "The idempotency store is unavailable",
                    IdempotencyDefaults.STORE_RETRY_AFTER);
            return false;
        }

        if (existing.getStatus() == Status.PENDING) {
            return refuseInProgress(response);
        }
        if (!Objects.equals(existing.getFingerprint(), hash)) {
            writer.write(response, ProblemType.KEY_REUSED, "This key was already used for a different request");
            return false;
        }
        replay(response, existing);
        return false;
    }

    /** The annotation on the method, else on its class; null if neither has one. */
    private static Idempotent findIdempotent(HandlerMethod method) {
        Idempotent onMethod = method.getMethodAnnotation(Idempotent.class);
        return onMethod != null ? onMethod : method.getBeanType().getAnnotation(Idempotent.class);
    }

    /**
     * Reads the one key from the named header.
     *
     * @throws InvalidIdempotencyKeyException if the header is missing, repeated, or not an acceptable key
     */
    private static String readKey(HttpServletRequest request, String headerName) {
        List<String> values = Collections.list(request.getHeaders(headerName));
        if (values.size() != 1) {
            throw new InvalidIdempotencyKeyException("Exactly one idempotency key header is required");
        }
        return IdempotencyKeyValidator.validate(values.get(0));
    }

    private boolean refuseBodyTooLarge(HttpServletResponse response) throws IOException {
        writer.write(response, ProblemType.BODY_TOO_LARGE,
                "The request body must be at most " + IdempotencyDefaults.MAX_BODY_SIZE_BYTES + " bytes");
        return false;
    }

    private boolean refuseInProgress(HttpServletResponse response) throws IOException {
        writer.write(response, ProblemType.REQUEST_IN_PROGRESS, "A request with this key is still being processed",
                IdempotencyDefaults.PENDING_RETRY_AFTER);
        return false;
    }

    /** Answers this request from the saved response; the stored record is not changed. */
    private void replay(HttpServletResponse response, IdempotencyRecord saved) throws IOException {
        response.setStatus(saved.getHttpStatus());
        response.setHeader(IdempotencyDefaults.REPLAY_HEADER, "true");
        if (saved.getBody() != null) {
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getOutputStream().write(saved.getBody().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void handOver(HttpServletRequest request, HttpServletResponse response, IdempotencyRecord claim) {
        request.setAttribute(IdempotencyDefaults.CLAIM_ATTRIBUTE, claim);
        CapturingResponse cr = WebUtils.getNativeResponse(response, CapturingResponse.class);
        if (cr != null) cr.startCapturing();
    }
}
