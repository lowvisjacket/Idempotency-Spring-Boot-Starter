package io.github.lowvisjacket.idempotency.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})  // allowed on methods and classes
@Retention(RetentionPolicy.RUNTIME)              // still present when the app runs
public @interface Idempotent {
    String header() default "Idempotency-Key";
    String ttl() default "24h";
}
