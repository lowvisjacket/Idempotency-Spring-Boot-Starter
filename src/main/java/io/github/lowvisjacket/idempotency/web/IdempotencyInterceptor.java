package io.github.lowvisjacket.idempotency.web;

import io.github.lowvisjacket.idempotency.annotation.Idempotent;
import io.github.lowvisjacket.idempotency.store.RedisLookup;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

public class IdempotencyInterceptor implements HandlerInterceptor {

    private final RedisLookup redisLookup;

    public IdempotencyInterceptor(RedisLookup redisLookup) {
        this.redisLookup = redisLookup;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) return true;

        Idempotent idempotent = method.getMethodAnnotation(Idempotent.class);
        if (idempotent == null) {
            idempotent = method.getBeanType().getAnnotation(Idempotent.class);
        }
        if (idempotent == null) return true;

        String key = request.getHeader("Idempotency-Key");
        return redisLookup.keyExists(key);
    }
}
