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
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

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
    private final CallerScope callerScope;

    /**
     * @param store       where records are claimed, read and kept
     * @param fingerprint computes the request fingerprint
     * @param writer      writes the error responses
     * @throws NullPointerException if any argument is null
     */
    public IdempotencyInterceptor(IdempotencyStore store, Fingerprint fingerprint, ProblemResponseWriter writer) {
        this(store, fingerprint, writer, new CallerScope());
    }

    /**
     * @param callerScope decides who the caller is and so the stored key (namespace, secret, extra identity headers)
     * @throws NullPointerException if any argument is null
     */
    public IdempotencyInterceptor(IdempotencyStore store, Fingerprint fingerprint, ProblemResponseWriter writer,
                                  CallerScope callerScope) {
        this.callerScope = Objects.requireNonNull(callerScope, "callerScope");
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
        // The same request is dispatched again for an async result and for an error page. The first dispatch already
        // decided; deciding again would find the request's own PENDING record and answer 409 to the client.
        if (request.getDispatcherType() != DispatcherType.REQUEST) return true;

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

        Duration ttl = TtlParser.parse(idempotent.ttl());

        String storedKey = callerScope.storedKey(request, key);
        if (storedKey == null) {
            writer.write(response, ProblemType.CALLER_NOT_IDENTIFIED,
                    "This endpoint is only available to identified callers");
            return false;
        }

        String hash;
        try {
            RequestWrapper wrapper = request instanceof RequestWrapper already ? already : RequestWrapper.withoutBody(request);
            hash = fingerprint.hash(wrapper);
        } catch (FingerprintException e) {
            return refuseBodyTooLarge(response);
        }

        IdempotencyRecord claimRecord = new IdempotencyRecord(storedKey, hash, null, null, 0, ttl);
        claimRecord.setOwner(UUID.randomUUID().toString());
        IdempotencyRecord existing;
        try {
            if (store.claim(claimRecord)) {
                handOver(request, response, claimRecord);
                return true;
            }

            Optional<IdempotencyRecord> found = store.find(storedKey);
            if (found.isEmpty()) {
                if (store.claim(claimRecord)) {
                    handOver(request, response, claimRecord);
                    return true;
                }
                return refuseInProgress(response);
            }
            existing = found.get();
        } catch (RuntimeException e) {
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
        if (!existing.isReplayable()) {
            writer.write(response, ProblemType.RESPONSE_NOT_REPLAYABLE,
                    "The original request finished with status " + existing.getHttpStatus()
                            + " but its response is too large or not text, so it cannot be repeated");
            return false;
        }
        replay(response, existing);
        return false;
    }

    /**
     * The annotation on the method, else on its class; null if neither has one. Both lookups see through
     * superclasses, interfaces and composed (meta) annotations, so a developer who put {@code @Idempotent} on a base
     * controller, an interface or an annotation of their own gets the protection they expect.
     */
    static Idempotent findIdempotent(HandlerMethod method) {
        Idempotent onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), Idempotent.class);
        return onMethod != null ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), Idempotent.class);
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
        if (saved.getContentType() != null) {
            response.setContentType(saved.getContentType());
        }
        replayHeaders(response, saved);
        if (saved.getBody() != null) {
            if (saved.getContentType() == null) {
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            }
            response.getOutputStream().write(saved.getBody().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Adds the saved headers to the replayed response.
     *
     * <p>Steps:
     * <ol>
     *   <li>Loop over {@link IdempotencyDefaults#REPLAYED_RESPONSE_HEADERS} ({@code allowed} is the current name).
     *       The loop is over the allow-list, not over the record, so a name the list does not know is never reached.</li>
     *   <li>Loop over {@code saved.getHeaders().entrySet()}. Skip an entry unless
     *       {@code allowed.equalsIgnoreCase(entry.getKey())} (a record from an older or odd writer may use another
     *       case).</li>
     *   <li>For each {@code value} in {@code entry.getValue()}:
     *     <ol type="a">
     *       <li>skip it if it is {@code null};</li>
     *       <li>skip it if it contains {@code '\r'} or {@code '\n'} ({@code value.indexOf('\r') >= 0 || ...}): a
     *           stored value like {@code "/x\r\nSet-Cookie: a=b"} would otherwise add a second header;</li>
     *       <li>otherwise {@code response.addHeader(allowed, value)}. Pass {@code allowed}, not the stored name, so the
     *           spelling is the list's. Use {@code addHeader}, not {@code setHeader}: {@code setHeader} would keep only
     *           the last of several values.</li>
     *     </ol></li>
     * </ol>
     *
     * <p>Notes: {@code saved.getHeaders()} is never null (an old record gives an empty map), so there is no null
     * check on the map and nothing to do for it. Because only list names are ever added, {@code Idempotency-Replayed}
     * and {@code Content-Type} cannot be touched; that is a result of the loop, not an extra check. It is called from
     * {@code replay} after the status and content type are set and before the body is written.
     *
     * @param response the replay being written
     * @param saved    the stored record
     */
    private void replayHeaders(HttpServletResponse response, IdempotencyRecord saved) {
        for (String header : IdempotencyDefaults.REPLAYED_RESPONSE_HEADERS) {
            for (Map.Entry<String, List<String>> entry : saved.getHeaders().entrySet()) {
                if (!header.equalsIgnoreCase(entry.getKey())) {
                    continue;
                }
                for (String value : entry.getValue()) {
                    if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                        continue;
                    }
                    response.addHeader(header, value);
                }
            }
        }
    }

    private void handOver(HttpServletRequest request, HttpServletResponse response, IdempotencyRecord claim) {
        request.setAttribute(IdempotencyDefaults.CLAIM_ATTRIBUTE, claim);
        CapturingResponse cr = WebUtils.getNativeResponse(response, CapturingResponse.class);
        if (cr != null) cr.startCapturing();
    }
}
