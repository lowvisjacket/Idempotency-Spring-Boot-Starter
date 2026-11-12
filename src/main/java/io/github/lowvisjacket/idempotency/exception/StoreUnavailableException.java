package io.github.lowvisjacket.idempotency.exception;

/**
 * Thrown instead of calling the store when the store is known to be down (the circuit breaker is open).
 *
 * <p>It is a {@link RuntimeException} on purpose: everything that talks to the store already treats any
 * {@code RuntimeException} as "store unavailable" (the interceptor answers 503, the response filter logs and moves
 * on), so no caller needs to change.
 *
 * <p>The message never contains details of the original failure (host names, ports, credentials can appear in those).
 */
public class StoreUnavailableException extends RuntimeException {

    /**
     * @param message what happened, in words that are safe to log; must not contain details of the store failure
     * @param cause   why the call was refused (for example the circuit breaker's "call not permitted"), or null
     */
    public StoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
