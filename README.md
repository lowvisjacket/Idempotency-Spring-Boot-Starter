# Idempotency Spring Boot Starter

[![CI](https://github.com/lowvisjacket/Idempotency-Spring-Boot-Starter/actions/workflows/ci.yml/badge.svg)](https://github.com/lowvisjacket/Idempotency-Spring-Boot-Starter/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)
[![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.x-6DB33F.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Make `POST`, `PUT` and `PATCH` endpoints safe to retry. Put `@Idempotent` on a controller method, and a repeated
request with the same `Idempotency-Key` gets the **saved response** instead of running the code a second time: no
double charges, no duplicate orders.

```java
@Idempotent(ttl = "24h")
@PostMapping("/orders")
public ResponseEntity<Order> create(@RequestBody NewOrder order) {
    return ResponseEntity.created(location).body(orders.place(order));
}
```

```
POST /orders                        POST /orders                        POST /orders
Idempotency-Key: abc123             Idempotency-Key: abc123             Idempotency-Key: abc123
  -> 201 Created (code ran)           -> 201 Created (replayed)           -> 201 Created (replayed)
                                         Idempotency-Replayed: true          Idempotency-Replayed: true
```

> **Status: in development, not yet released.** The behaviour below is implemented and tested; the coordinates in the
> quick start are what the first release will use.

## Requirements

- Java 21
- Spring Boot 4.x with Spring MVC (servlet applications; WebFlux is not supported)
- Redis (the only store for now; a JDBC store is planned)

## Quick start

**1. Add the starter and Redis.**

```xml
<dependency>
    <groupId>io.github.lowvisjacket</groupId>
    <artifactId>idempotency-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

**2. Point Spring at Redis** as you normally would (`spring.data.redis.host`, and so on). There is nothing to configure
for the library itself: with a Redis connection present it switches itself on.

**3. Annotate the endpoints** that must not run twice, on a method or on a whole controller class.

**4. Clients send a key** with every such request: a unique value per *intended* operation, such as a UUID, reused
unchanged when they retry.

```
curl -X POST http://localhost:8080/orders \
     -H 'Idempotency-Key: 7f3a1c9e-6d0b-4a52-9a7e-2b1e5c8d4f10' \
     -H 'Content-Type: application/json' \
     -d '{"item":"book"}'
```

## `@Idempotent`

| Attribute | Default | Meaning |
|---|---|---|
| `header` | `Idempotency-Key` | Request header that carries the key. |
| `ttl` | `24h` | How long a key is remembered. A whole number plus one unit: `s`, `m`, `h` or `d` (`"30s"`, `"10m"`, `"24h"`, `"7d"`). At most 365 days. |

A key is 1 to 255 printable ASCII characters with no spaces. Endpoints without the annotation are never touched.

## What the client sees

| Situation | Response |
|---|---|
| First request with this key | The controller runs; the client gets its response, and a copy is saved. |
| Same key, same request, after the first finished | The **saved response** again, with `Idempotency-Replayed: true`. The controller does not run. |
| Same key while the first is still running | `409` with `Retry-After: 1`. |
| Same key, but a **different** request | `422`. The saved response is never shown. |
| Key missing, repeated, or not acceptable | `400`. |
| Request body larger than 2 KB | `413`. |
| Caller cannot be identified, with `idempotency.anonymous-access=reject` | `401`. |
| Same key, but the first request finished with a response that could not be saved, with `idempotency.on-unreplayable-response=block` | `409`. The controller does not run again. |
| The store cannot be reached | `503` with `Retry-After: 5`. The request is refused rather than run unprotected. |

Refusals are `application/problem+json` ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)) with a stable `type`:

```json
{
  "type": "urn:idempotency:key-reused",
  "title": "Idempotency key reused with a different request",
  "status": 422
}
```

The types are `urn:idempotency:invalid-key`, `request-in-progress`, `body-too-large`, `key-reused`,
`response-not-replayable`, `caller-not-identified` and `store-unavailable`. Error text never repeats the key or the store's own error message.

### What counts as "the same request"

The method, path, query string, media type (without parameters such as `charset`) and body. Text bodies are
Unicode-normalised, so the same text typed two ways matches; binary bodies are compared byte for byte. Form posts
(`application/x-www-form-urlencoded`) are compared by their fields, whatever order they come in. Other headers are not
part of it.

Keys are also **scoped to the caller**: who the caller is (hashed, never stored as is) is part of the stored key, so two
users who happen to send the same key never see each other's responses. The caller is the authenticated principal's
name; failing that, the `Authorization` header; plus any headers listed in `idempotency.caller-headers` (a tenant id,
say). A request with none of these is anonymous. By default all anonymous requests share one space, so anyone who knows
a key can read its saved response: set `idempotency.anonymous-access=reject` to answer them with `401` instead.

Redis keys can also carry a prefix (`idempotency.namespace`, so several applications can share a Redis) and be derived
with a secret (`idempotency.key-secret`, so a Redis reader cannot recompute them from guessed user names).

### What is saved and replayed

The status, the body, the `Content-Type`, and these headers: `Location`, `Content-Location`, `ETag` and
`Content-Language`, `Cache-Control`, `Expires`, `Pragma` and `Vary`. Nothing else is kept. In particular `Set-Cookie` and other per-response or private headers are
never written to the store and never replayed.

### When a response is *not* saved

The key is released instead, so a retry runs the controller again. The client still receives the response in full.

- the status is `5xx`, or the controller threw an exception (a failure should be retried, not replayed; set
  `idempotency.on-server-error=replay` to save `5xx` responses instead);
- the status is `401`, `403`, `407`, `408`, `425` or `429` (the request was refused or not processed, and a retry may
  well succeed);
- the body is larger than `idempotency.max-response-body-bytes` (64 KB by default);
- the body is not UTF-8 text (for example a PDF or an image).

Other `3xx` and `4xx` responses are saved and replayed like any other.

If the controller already did its work and then returned a response that cannot be saved (too large, binary), releasing
the key means a retry does the work again. Where that is worse than a refusal, set
`idempotency.on-unreplayable-response=block`: a retry then gets `409 response-not-replayable`.

## Configuration

All settings are optional.

```properties
idempotency.enabled=true
idempotency.max-response-body-bytes=65536

# Prefix for Redis keys: letters, digits, . _ - (default: none)
idempotency.namespace=orders
# Derive keys with HMAC-SHA256 instead of plain SHA-256
idempotency.key-secret=change-me
# Extra headers that identify the caller
idempotency.caller-headers=X-Tenant-Id
# shared | reject
idempotency.anonymous-access=shared
# release | block
idempotency.on-unreplayable-response=release
# release | replay
idempotency.on-server-error=release

idempotency.circuit-breaker.enabled=true
idempotency.circuit-breaker.sliding-window-size=20
idempotency.circuit-breaker.minimum-number-of-calls=10
idempotency.circuit-breaker.failure-rate-threshold=50
idempotency.circuit-breaker.wait-duration-in-open-state=30s
idempotency.circuit-breaker.permitted-calls-in-half-open-state=3
```

`idempotency.enabled=false` switches the whole library off. Bad values stop the application at startup and name the
property.

### Circuit breaker

If [Resilience4j](https://resilience4j.readme.io/) is on the classpath, the store is wrapped in a circuit breaker.
When Redis keeps failing, the breaker opens and idempotent requests are answered with `503` straight away instead of
each one waiting on a dead connection. It tries again after `wait-duration-in-open-state`.

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-circuitbreaker</artifactId>
    <version>2.3.0</version>
</dependency>
```

Without Resilience4j the library works the same, just without the breaker.

### Replacing the parts

Every piece is a bean that backs off if you define your own:

- an `IdempotencyStore` bean replaces the Redis store (it is still wrapped by the breaker);
- a `CircuitBreaker` bean named `idempotencyCircuitBreaker` replaces the default breaker;
- a `CallerScope` bean replaces how the caller is identified;
- the interceptor, fingerprint calculator and problem writer can be replaced the same way.

The interceptor runs after your own interceptors, so authentication and similar checks happen before a saved response
is served. If no `IdempotencyStore` bean exists, an application with `@Idempotent` endpoints refuses to start.

## Limitations

Please read these before relying on the library.

- **Async handlers are refused at startup.** An `@Idempotent` method that returns `Callable`, `DeferredResult`,
  `CompletionStage`, a reactive type, an emitter or `StreamingResponseBody` stops the application with a message naming
  the method. Its answer does not exist when the method returns, so it cannot be saved.
- **Request bodies over 2 KB are rejected** (`413`) on `@Idempotent` endpoints, because the body is read into memory to
  fingerprint it. Endpoints without the annotation receive large and chunked bodies unchanged.
- **Large and binary responses are not replayed.** They are delivered normally, but a retry runs the code again (see
  above).
- **A crash mid-request** leaves the key marked as running until its TTL ends, so retries get `409` until then. Use a
  TTL that matches how long you can live with that.
- **Only `POST`, `PUT` and `PATCH` bodies are fingerprinted.** Other methods are fingerprinted on method, path and query.
- **Functional endpoints** (`RouterFunction`) are not covered; only annotated controllers are.
- **Method security does not run again** when a response is replayed. Put access checks in a filter or interceptor.
- **If a record's TTL ends while the first request is still running,** a retry can run the handler a second time. Choose
  a TTL much longer than your slowest request. (A late finisher can no longer overwrite or delete the newer request's
  record.)
- **There is no limit on how many keys one caller can create.** Rate-limit at the edge.
- Redis is the only store so far.

## Building

```
./mvnw test
```

The tests start a real Redis with [Testcontainers](https://testcontainers.com/), so Docker must be running.

## License

[Apache License 2.0](LICENSE)
