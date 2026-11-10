package io.github.lowvisjacket.idempotency.exception;

public class RedisException extends RuntimeException {
    public RedisException(String message) {
        super(message);
    }
}
