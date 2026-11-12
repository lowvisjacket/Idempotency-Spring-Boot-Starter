package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.annotation.Idempotent;
import io.github.lowvisjacket.idempotency.autoconfigure.IdempotencyAutoConfiguration;
import io.github.lowvisjacket.idempotency.store.IdempotencyStore;
import io.github.lowvisjacket.idempotency.web.AsyncHandlerGuard;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * An {@code @Idempotent} handler that answers after its method returns would leave a PENDING record behind until the
 * TTL ends, so the application must refuse to start with it. Two parts: which handlers count as async, and that the
 * check really stops a Spring application.
 */
class AsyncHandlerGuardTests {

    @RestController
    static class Handlers {
        public String plain() { return ""; }
        public void nothing() { }
        public int number() { return 1; }
        public Object anything() { return ""; }
        public ResponseEntity<String> entity() { return null; }
        public ResponseEntity<Map<String, Object>> entityOfMap() { return null; }
        public List<String> list() { return null; }
        public Optional<String> optional() { return null; }

        public Callable<String> callable() { return null; }
        public CompletableFuture<String> future() { return null; }
        public CompletionStage<String> stage() { return null; }
        public DeferredResult<String> deferred() { return null; }
        public WebAsyncTask<String> task() { return null; }
        public ResponseBodyEmitter emitter() { return null; }
        public SseEmitter sse() { return null; }
        public StreamingResponseBody stream() { return null; }
        public ResponseEntity<Callable<String>> entityOfCallable() { return null; }
        public ResponseEntity<DeferredResult<String>> entityOfDeferred() { return null; }
        public ResponseEntity<StreamingResponseBody> entityOfStream() { return null; }
        public ResponseEntity<CompletableFuture<String>> entityOfFuture() { return null; }
        public ResponseEntity<SseEmitter> entityOfSse() { return null; }
        public reactor.core.publisher.Mono<String> mono() { return null; }
        public reactor.core.publisher.Flux<String> flux() { return null; }
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Handlers(), Handlers.class.getMethod(name));
    }

    @ParameterizedTest(name = "{0} is synchronous")
    @ValueSource(strings = {"plain", "nothing", "number", "anything", "entity", "entityOfMap", "list", "optional"})
    void ordinaryReturnTypesAreSynchronous(String name) throws Exception {
        assertFalse(AsyncHandlerGuard.isAsync(handler(name)));
    }

    @ParameterizedTest(name = "{0} is async")
    @ValueSource(strings = {"callable", "future", "stage", "deferred", "task", "emitter", "sse", "stream",
            "entityOfCallable", "entityOfDeferred", "entityOfStream", "entityOfFuture", "entityOfSse"})
    void typesThatAnswerLaterAreAsync(String name) throws Exception {
        assertTrue(AsyncHandlerGuard.isAsync(handler(name)));
    }

    @ParameterizedTest(name = "{0} is async (reactive)")
    @ValueSource(strings = {"mono", "flux"})
    void reactiveTypesAreAsync(String name) throws Exception {
        Assumptions.assumeTrue(ClassUtils.isPresent("reactor.core.publisher.Mono", getClass().getClassLoader()));
        assertTrue(AsyncHandlerGuard.isAsync(handler(name)));
    }

    @Test
    void isAsyncRejectsNull() {
        assertThrows(NullPointerException.class, () -> AsyncHandlerGuard.isAsync(null));
    }

    @RestController
    static class Mixed {
        @Idempotent @PostMapping("/a") public String sync() { return ""; }
        @Idempotent @PostMapping("/b") public Callable<String> badCallable() { return null; }
        @Idempotent @PostMapping("/c") public DeferredResult<String> badDeferred() { return null; }
        @PostMapping("/d") public Callable<String> freeAsync() { return null; }
    }

    @RestController
    @Idempotent
    static class ClassLevel {
        @PostMapping("/x") public String sync() { return ""; }
        @PostMapping("/y") public CompletableFuture<String> async() { return null; }
    }

    @RestController
    static class NoAnnotationAnywhere {
        @GetMapping("/z") public Callable<String> async() { return null; }
    }

    private static HandlerMethod on(Object bean, String name) throws NoSuchMethodException {
        return new HandlerMethod(bean, bean.getClass().getMethod(name));
    }

    @Test
    void anEmptyListIsFine() {
        assertDoesNotThrow(() -> AsyncHandlerGuard.validate(List.of()));
    }

    @Test
    void validateRejectsNull() {
        assertThrows(NullPointerException.class, () -> AsyncHandlerGuard.validate(null));
    }

    @Test
    void synchronousIdempotentHandlersPass() throws Exception {
        assertDoesNotThrow(() -> AsyncHandlerGuard.validate(List.of(on(new Mixed(), "sync"))));
    }

    @Test
    void anAsyncHandlerWithoutTheAnnotationIsNotOurBusiness() throws Exception {
        assertDoesNotThrow(() -> AsyncHandlerGuard.validate(List.of(
                on(new Mixed(), "freeAsync"), on(new NoAnnotationAnywhere(), "async"))));
    }

    @Test
    void anIdempotentAsyncHandlerStopsStartupAndIsNamed() throws Exception {
        assertThatThrownBy(() -> AsyncHandlerGuard.validate(List.of(on(new Mixed(), "badCallable"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@Idempotent")
                .hasMessageContaining(Mixed.class.getName() + "#badCallable");
    }

    @Test
    void everyOffenderIsListedSortedAndTheGoodOnesAreNot() throws Exception {
        assertThatThrownBy(() -> AsyncHandlerGuard.validate(List.of(
                on(new Mixed(), "sync"), on(new Mixed(), "badDeferred"), on(new Mixed(), "badCallable"),
                on(new Mixed(), "freeAsync"))))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> {
                    String m = e.getMessage();
                    assertThat(m).contains("#badCallable").contains("#badDeferred")
                            .doesNotContain("#sync").doesNotContain("#freeAsync");
                    assertThat(m.indexOf("#badCallable")).isLessThan(m.indexOf("#badDeferred"));
                });
    }

    @Test
    void theAnnotationOnTheClassCountsForItsAsyncMethodsOnly() throws Exception {
        assertDoesNotThrow(() -> AsyncHandlerGuard.validate(List.of(on(new ClassLevel(), "sync"))));
        assertThatThrownBy(() -> AsyncHandlerGuard.validate(List.of(on(new ClassLevel(), "async"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ClassLevel.class.getName() + "#async");
    }

    @Test
    void theSameHandlerSeenTwiceIsListedOnce() throws Exception {
        assertThatThrownBy(() -> AsyncHandlerGuard.validate(List.of(
                on(new Mixed(), "badCallable"), on(new Mixed(), "badCallable"))))
                .satisfies(e -> assertThat(e.getMessage().split("#badCallable", -1)).hasSize(2));
    }

    @Configuration
    @EnableWebMvc
    @Import(Mixed.class)
    static class AppWithAsyncIdempotent {
    }

    @Configuration
    @EnableWebMvc
    @Import(NoAnnotationAnywhere.class)
    static class AppWithAsyncButNotIdempotent {
    }

    @Configuration
    @EnableWebMvc
    @Import(OnlySync.class)
    static class AppWithOnlySync {
    }

    @RestController
    static class OnlySync {
        @Idempotent @PostMapping("/ok") public String ok() { return ""; }
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, IdempotencyAutoConfiguration.class))
            .withBean("store", IdempotencyStore.class, () -> mock(IdempotencyStore.class));

    @Test
    void anApplicationWithAnAsyncIdempotentHandlerFailsToStart() {
        runner.withUserConfiguration(AppWithAsyncIdempotent.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("#badCallable")
                    .hasStackTraceContaining("#badDeferred");
        });
    }

    @Test
    void anApplicationWithOnlySynchronousIdempotentHandlersStarts() {
        runner.withUserConfiguration(AppWithOnlySync.class).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void anAsyncHandlerThatIsNotIdempotentDoesNotStopTheApplication() {
        runner.withUserConfiguration(AppWithAsyncButNotIdempotent.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void withTheLibrarySwitchedOffNothingIsCheckedAndNothingStops() {
        runner.withPropertyValues("idempotency.enabled=false").withUserConfiguration(AppWithAsyncIdempotent.class)
                .run(context -> assertThat(context).hasNotFailed());
    }
}
