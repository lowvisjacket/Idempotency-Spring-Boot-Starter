package io.github.lowvisjacket.idempotency.web;

/**
 * The error cases this library reports to clients, each with a fixed HTTP status, a stable {@code type}
 * identifier and a short {@code title} (the members of an RFC 9457 problem document).
 *
 * <p>The {@code type} values are part of the library's public contract: clients may switch on them, so they
 * must never change once released. Situation-specific text belongs in the {@code detail} member, not here.
 *
 * @see ProblemResponseWriter
 */
public enum ProblemType {

    /** The idempotency key is missing or not acceptable. */
    INVALID_KEY(400, "urn:idempotency:invalid-key", "Invalid idempotency key"),

    /** A request with this key is still being processed. */
    REQUEST_IN_PROGRESS(409, "urn:idempotency:request-in-progress", "Request already in progress"),

    /** The request body is larger than the library will buffer. */
    BODY_TOO_LARGE(413, "urn:idempotency:body-too-large", "Request body too large"),

    /** The key was already used for a different request. */
    KEY_REUSED(422, "urn:idempotency:key-reused", "Idempotency key reused with a different request"),

    /** The request finished, but its response was too large or not text, so it cannot be repeated; it is not run again. */
    RESPONSE_NOT_REPLAYABLE(409, "urn:idempotency:response-not-replayable", "Original response cannot be replayed"),

    /** The store that tracks keys cannot be reached, so the request is refused rather than run unprotected. */
    STORE_UNAVAILABLE(503, "urn:idempotency:store-unavailable", "Idempotency store unavailable");

    private final int status;
    private final String type;
    private final String title;

    ProblemType(int status, String type, String title) {
        this.status = status;
        this.type = type;
        this.title = title;
    }

    /** @return the HTTP status code sent with this problem */
    public int status() {
        return status;
    }

    /** @return the stable identifier (a URN) sent as the problem's {@code type} */
    public String type() {
        return type;
    }

    /** @return the short, fixed, human-readable summary sent as the problem's {@code title} */
    public String title() {
        return title;
    }
}
