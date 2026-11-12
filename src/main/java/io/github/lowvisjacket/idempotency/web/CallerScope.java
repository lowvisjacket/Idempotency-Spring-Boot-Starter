package io.github.lowvisjacket.idempotency.web;

import io.github.lowvisjacket.idempotency.store.StoredKeys;
import jakarta.servlet.http.HttpServletRequest;

import java.security.Principal;
import java.util.List;
import java.util.Objects;

/**
 * Decides who a request belongs to, and so which stored key it uses. Define your own bean of this type to take over
 * (for example to scope by tenant or by API key), since nothing but {@code getUserPrincipal()} is known to the
 * library by default.
 *
 * <p>The default identity is, in order:
 * <ol>
 *   <li>the servlet principal's name, if there is one;</li>
 *   <li>otherwise a digest input built from the {@code Authorization} header, if it is present, so an application that
 *       authenticates with a bearer token or API key (and does not set the servlet principal) still keeps its users
 *       apart;</li>
 *   <li>otherwise anonymous: every anonymous caller shares one namespace. That is inherent, and it means anyone who
 *       knows (or guesses) a key can read the saved response for it. Use {@code anonymous-access=reject} to refuse
 *       anonymous callers, or scope them yourself.</li>
 * </ol>
 * The values of the configured extra headers (for example {@code X-Tenant-Id}) are added to whichever identity was
 * found. Missing configured headers contribute an empty value.
 */
public class CallerScope {

    private final String namespace;
    private final String secret;
    private final List<String> extraHeaders;
    private final boolean rejectAnonymous;

    /** No namespace, no secret, no extra headers, anonymous callers allowed. */
    public CallerScope() {
        this(null, null, List.of(), false);
    }

    public CallerScope(String namespace, String secret, List<String> extraHeaders, boolean rejectAnonymous) {
        this.namespace = namespace;
        this.secret = secret;
        this.extraHeaders = List.copyOf(Objects.requireNonNull(extraHeaders, "extraHeaders"));
        this.rejectAnonymous = rejectAnonymous;
        StoredKeys.build(namespace, secret, null, "x");
    }

    /**
     * @return the identity of the caller, or null for an anonymous one
     */
    protected String identify(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        String identity = null;
        if (principal != null) {
            identity = principal.getName() == null ? "" : principal.getName();
        } else {
            String authorization = request.getHeader("Authorization");
            if (authorization != null && !authorization.isEmpty()) {
                identity = "\u0000authorization\u0000" + authorization;
            }
        }
        if (identity == null || extraHeaders.isEmpty()) {
            return identity;
        }
        StringBuilder out = new StringBuilder(identity);
        for (String header : extraHeaders) {
            String value = request.getHeader(header);
            out.append('\u0000').append(header.toLowerCase(java.util.Locale.ROOT)).append('=').append(value == null ? "" : value);
        }
        return out.toString();
    }

    /**
     * @return the stored key for this request, or null if the caller is anonymous and anonymous callers are refused
     * @throws io.github.lowvisjacket.idempotency.exception.InvalidIdempotencyKeyException if the key is not acceptable
     */
    public String storedKey(HttpServletRequest request, String idempotencyKey) {
        String caller = identify(request);
        if (caller == null && rejectAnonymous) {
            return null;
        }
        return StoredKeys.build(namespace, secret, caller, idempotencyKey);
    }
}
