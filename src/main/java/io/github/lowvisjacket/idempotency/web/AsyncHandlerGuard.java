package io.github.lowvisjacket.idempotency.web;

import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpEntity;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Collection;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;

/**
 * Refuses, at startup, {@code @Idempotent} on handlers whose answer is produced <em>after</em> the method returns.
 *
 * <p><b>Why.</b> The library saves the response when the request thread comes back out of the controller. A handler
 * that returns a {@link Callable}, {@link DeferredResult}, {@link CompletionStage}, {@link WebAsyncTask}, a reactive
 * type (Mono, Flux, ...), a {@link ResponseBodyEmitter} (including {@code SseEmitter}) or a
 * {@link StreamingResponseBody} has no answer yet at that point. The record would stay {@code PENDING} until its TTL
 * ended, and every retry in the meantime would be refused with 409. Failing at startup, with the handler named, is
 * kinder than that.
 *
 * <p><b>What counts as async.</b> The declared return type of the method, or the type inside a
 * {@code ResponseEntity} / {@code HttpEntity} (so {@code ResponseEntity<Callable<String>>} is async too). Plain
 * types, including {@code Object}, are treated as synchronous.
 *
 * <p><b>What is checked.</b> Only handlers that are {@code @Idempotent}, by the same rule the interceptor uses: the
 * annotation on the method, else on its class. An async handler without the annotation is not this library's business.
 *
 * <p>Stateless; every method is static.
 */
public final class AsyncHandlerGuard {

    private static final Class<?>[] ASYNC_TYPES = {
            Callable.class, CompletionStage.class, DeferredResult.class, WebAsyncTask.class,
            ResponseBodyEmitter.class, StreamingResponseBody.class
    };

    private AsyncHandlerGuard() {
    }

    /**
     * @param method a handler method
     * @return true if its answer is produced after the method returns
     * @throws NullPointerException if {@code method} is null
     */
    public static boolean isAsync(HandlerMethod method) {
        Objects.requireNonNull(method, "method");
        ResolvableType type = ResolvableType.forMethodReturnType(method.getMethod(), method.getBeanType());
        if (HttpEntity.class.isAssignableFrom(type.toClass())) {
            type = type.as(HttpEntity.class).getGeneric(0);
        }
        if (isKotlinSuspend(method)) {
            return true;
        }
        Class<?> raw = type.resolve(Object.class);
        for (Class<?> async : ASYNC_TYPES) {
            if (async.isAssignableFrom(raw)) {
                return true;
            }
        }
        return ReactiveAdapterRegistry.getSharedInstance().getAdapter(raw) != null;
    }

    /** A Kotlin {@code suspend} function compiles to a method taking a trailing {@code Continuation}. */
    private static boolean isKotlinSuspend(HandlerMethod method) {
        Class<?>[] params = method.getMethod().getParameterTypes();
        return params.length > 0 && "kotlin.coroutines.Continuation".equals(params[params.length - 1].getName());
    }

    /**
     * @param methods every handler method of the application
     * @throws IllegalStateException if any is {@code @Idempotent} and async; the message names every such handler
     *                               as {@code Class#method}, sorted, and says what to do
     * @throws NullPointerException  if {@code methods} is null
     */
    public static void validate(Collection<HandlerMethod> methods) {
        Objects.requireNonNull(methods, "methods");
        TreeSet<String> offenders = new TreeSet<>();
        for (HandlerMethod method : methods) {
            if (IdempotencyInterceptor.findIdempotent(method) != null && isAsync(method)) {
                offenders.add(method.getBeanType().getName() + "#" + method.getMethod().getName());
            }
        }
        if (!offenders.isEmpty()) {
            throw new IllegalStateException("@Idempotent is not supported on handlers that complete asynchronously "
                    + "(Callable, DeferredResult, CompletionStage, reactive types, emitters, StreamingResponseBody). "
                    + "Remove @Idempotent from, or make synchronous: " + String.join(", ", offenders));
        }
    }
}
