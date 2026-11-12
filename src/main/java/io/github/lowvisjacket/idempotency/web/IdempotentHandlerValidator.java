package io.github.lowvisjacket.idempotency.web;

import io.github.lowvisjacket.idempotency.annotation.Idempotent;
import io.github.lowvisjacket.idempotency.config.TtlParser;
import org.springframework.web.method.HandlerMethod;

import java.util.Collection;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Startup checks on every {@code @Idempotent} handler, so a mistake in the annotation stops the application at boot
 * instead of failing (or silently not protecting) on every request.
 */
public final class IdempotentHandlerValidator {

    private IdempotentHandlerValidator() {
    }

    /**
     * Refuses async handlers (see {@link AsyncHandlerGuard}), a {@code ttl} that {@link TtlParser} rejects and a blank
     * {@code header}. One exception names every offender.
     *
     * @throws IllegalStateException if any {@code @Idempotent} handler is invalid
     */
    public static void validate(Collection<HandlerMethod> methods) {
        Objects.requireNonNull(methods, "methods");
        AsyncHandlerGuard.validate(methods);
        TreeSet<String> problems = new TreeSet<>();
        for (HandlerMethod method : methods) {
            Idempotent idempotent = IdempotencyInterceptor.findIdempotent(method);
            if (idempotent == null) continue;
            String where = method.getBeanType().getName() + "#" + method.getMethod().getName();
            try {
                TtlParser.parse(idempotent.ttl());
            } catch (IllegalArgumentException e) {
                problems.add(where + " has an invalid ttl (use a number and s, m, h or d, at most 365d)");
            }
            if (idempotent.header().isBlank()) {
                problems.add(where + " has a blank header name");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid @Idempotent configuration: " + String.join("; ", problems));
        }
    }

    /**
     * Used when no {@code IdempotencyStore} exists: the library has backed off, so an {@code @Idempotent} endpoint
     * would run on every retry with nothing saying so.
     *
     * @throws IllegalStateException if any handler is {@code @Idempotent}
     */
    public static void requireNoIdempotentHandlers(Collection<HandlerMethod> methods) {
        Objects.requireNonNull(methods, "methods");
        TreeSet<String> offenders = new TreeSet<>();
        for (HandlerMethod method : methods) {
            if (IdempotencyInterceptor.findIdempotent(method) != null) {
                offenders.add(method.getBeanType().getName() + "#" + method.getMethod().getName());
            }
        }
        if (!offenders.isEmpty()) {
            throw new IllegalStateException("@Idempotent endpoints exist but there is no IdempotencyStore bean, so they "
                    + "would not be protected at all. Configure Redis (a StringRedisTemplate), define your own "
                    + "IdempotencyStore bean, or set idempotency.enabled=false and remove the annotations. Endpoints: "
                    + String.join(", ", offenders));
        }
    }
}
