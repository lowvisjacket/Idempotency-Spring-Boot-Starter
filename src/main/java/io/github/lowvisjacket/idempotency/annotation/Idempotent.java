package io.github.lowvisjacket.idempotency.annotation;

import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    String header() default IdempotencyDefaults.IDEMPOTENCY_KEY_HEADER;
    String ttl() default IdempotencyDefaults.DEFAULT_TTL;
}
