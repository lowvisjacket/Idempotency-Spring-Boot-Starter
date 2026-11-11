package io.github.lowvisjacket.idempotency.exception;

/** Thrown when a client-supplied idempotency key is missing or not acceptable. The interceptor answers 400. */
public class InvalidIdempotencyKeyException extends RuntimeException {
    public InvalidIdempotencyKeyException(String message) {
        super(message);
    }
}
