package io.github.lowvisjacket.idempotency.exception;

public class FingerprintException extends RuntimeException {
    public FingerprintException(String message) {
        super(message);
    }
}
