package io.github.lowvisjacket.idempotency.fingerprint;

import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.exception.FingerprintException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Buffers the body of write requests so it can be read more than once.
 *
 * <p>A servlet request body can only be read a single time. The fingerprint needs the body, and the controller
 * needs it afterwards, so this filter reads it once into a {@link RequestWrapper} and passes that wrapper down
 * the filter chain in place of the original request.
 *
 * <p>Behavior, by request:
 * <ul>
 *   <li><b>Not POST, PUT or PATCH</b> - passed through untouched.</li>
 *   <li><b>Declared body larger than the cap</b> - passed through untouched (not buffered) and flagged with the
 *       request attribute {@link IdempotencyDefaults#BODY_EXCEEDS_CAP_ATTRIBUTE}. The interceptor, which can see
 *       {@code @Idempotent}, decides whether that is an error. This filter runs before Spring has chosen a
 *       controller, so it must not reject requests for endpoints that are not idempotent.</li>
 *   <li><b>Body found to be larger than the cap while reading</b> (no declared length, for example a chunked
 *       upload) - answered with {@code 413 Content Too Large}. The original stream has already been consumed, so
 *       the request cannot be passed on.</li>
 *   <li><b>Otherwise</b> - wrapped, and the wrapper is passed down the chain.</li>
 * </ul>
 *
 * <p>Not registered automatically; it must be added as a servlet filter bean.
 *
 * @see RequestWrapper
 * @see IdempotencyDefaults
 */
public class RequestFilter extends OncePerRequestFilter {

    /** Methods whose request bodies are buffered. Everything else is passed through untouched. */
    private static final Set<String> WRAPPED_ALLOWED_METHODS = Set.of("PATCH", "POST", "PUT");

    /**
     * Applies the rules described on the class to a single request.
     *
     * <p>Exactly one outcome happens per request: the original request is passed on, a {@link RequestWrapper}
     * is passed on, or a 413 response is written and the chain is not called.
     *
     * @param request     the incoming request
     * @param response    the response, used only to report a body that is too large
     * @param filterChain the rest of the filter chain
     * @throws ServletException if a later filter or servlet fails
     * @throws IOException      if reading the request body fails, or a later filter or servlet fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (!WRAPPED_ALLOWED_METHODS.contains(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (request.getContentLengthLong() > IdempotencyDefaults.MAX_BODY_SIZE_BYTES) {
            request.setAttribute(IdempotencyDefaults.BODY_EXCEEDS_CAP_ATTRIBUTE, Boolean.TRUE);
            filterChain.doFilter(request, response);
            return;
        }

        RequestWrapper wrapper;
        try {
            wrapper = new RequestWrapper(request);
        } catch (FingerprintException exception) {
            response.sendError(HttpStatus.CONTENT_TOO_LARGE.value(), exception.getMessage());
            return;
        }

        filterChain.doFilter(wrapper, response);
    }
}
