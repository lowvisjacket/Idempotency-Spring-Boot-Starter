package io.github.lowvisjacket.idempotency.web;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Writes error responses as RFC 9457 problem documents ({@code application/problem+json}).
 *
 * <p>The library's filter and interceptor run before or around Spring MVC's own error handling, so they write
 * their errors directly with this class. That keeps every error the same shape and does not depend on the host
 * application having a {@code @ControllerAdvice}.
 *
 * <p>The body has the members {@code type}, {@code title} and {@code status} (all from {@link ProblemType}) and,
 * when one is given, {@code detail}. Instances are stateless and safe to share between threads.
 *
 * <p><b>Write once per response.</b> The servlet API cannot remove a header, so if this is called twice on the same
 * response, a {@code Retry-After} set by the first call stays even if the second call asks for none. The status,
 * content type and body of the second call do replace the first.
 *
 * <p><b>Security:</b> {@code detail} is written as given. Callers must describe what went wrong and must not
 * copy untrusted request input into it.
 *
 * @see ProblemType
 */
public class ProblemResponseWriter {

    /** The media type of a problem document. */
    public static final String CONTENT_TYPE = "application/problem+json";

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper the mapper used to produce the JSON body
     * @throws NullPointerException if {@code objectMapper} is null
     */
    public ProblemResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Writes a problem response with no {@code Retry-After} header.
     *
     * @param response the response to write to
     * @param problem  which problem to report
     * @param detail   an optional explanation; omitted from the body when null or blank
     * @return true if the problem was written, false if the response was already committed and could not be changed
     * @throws IOException          if writing the body fails
     * @throws NullPointerException if {@code response} or {@code problem} is null
     */
    public boolean write(HttpServletResponse response, ProblemType problem, String detail) throws IOException {
        return write(response, problem, detail, null);
    }

    /**
     * Writes a problem response, replacing anything not yet sent.
     *
     * <p>Headers already set on the response (for example CORS headers) are kept, but any body that was
     * buffered but not yet sent is discarded. The status, {@code Content-Type}, {@code Content-Length} and, when
     * requested, {@code Retry-After} are then set and the JSON body is written. The body is built before the
     * response is touched, so a failure to produce it leaves the response unchanged.
     *
     * @param response   the response to write to
     * @param problem    which problem to report
     * @param detail     an optional explanation; omitted from the body when null or blank
     * @param retryAfter how long the client should wait before retrying, sent as whole seconds rounded up in the
     *                   {@code Retry-After} header; null means no header
     * @return true if the problem was written, false if the response was already committed and could not be changed
     * @throws IOException              if writing the body fails
     * @throws NullPointerException     if {@code response} or {@code problem} is null
     * @throws IllegalArgumentException if {@code retryAfter} is zero or negative
     */
    public boolean write(HttpServletResponse response, ProblemType problem, String detail, Duration retryAfter) throws IOException {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(problem, "problem");
        if (retryAfter != null && (retryAfter.isZero() || retryAfter.isNegative())) {
            throw new IllegalArgumentException("retryAfter must be positive");
        }

        byte[] body = serialize(problem, detail);

        if (response.isCommitted()) {
            return false;
        }

        response.resetBuffer();
        response.setStatus(problem.status());
        response.setContentType(CONTENT_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (retryAfter != null) {
            response.setHeader("Retry-After", Long.toString(wholeSecondsRoundedUp(retryAfter)));
        }
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        return true;
    }

    private byte[] serialize(ProblemType problem, String detail) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("type", problem.type());
        document.put("title", problem.title());
        document.put("status", problem.status());
        if (detail != null && !detail.isBlank()) {
            document.put("detail", detail);
        }
        return objectMapper.writeValueAsBytes(document);
    }

    private long wholeSecondsRoundedUp(Duration duration) {
        return duration.getNano() > 0 ? duration.getSeconds() + 1 : duration.getSeconds();
    }
}
