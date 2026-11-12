package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.exception.StoreUnavailableException;
import io.github.lowvisjacket.idempotency.fingerprint.Fingerprint;
import io.github.lowvisjacket.idempotency.fingerprint.RequestWrapper;
import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;
import io.github.lowvisjacket.idempotency.store.CircuitBreakerIdempotencyStore;
import io.github.lowvisjacket.idempotency.store.IdempotencyStore;
import io.github.lowvisjacket.idempotency.web.CapturingResponse;
import io.github.lowvisjacket.idempotency.web.IdempotencyInterceptor;
import io.github.lowvisjacket.idempotency.web.ProblemResponseWriter;
import io.github.lowvisjacket.idempotency.web.ResponseCaptureFilter;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CircuitBreakerIdempotencyStore}, written to break it.
 *
 * <p>The delegate is a Mockito mock so every call (and every call that must NOT happen) can be counted exactly.
 * The breaker is a real Resilience4j {@link CircuitBreaker} with a tiny window (2 calls, opens when both fail), and
 * states are forced with {@code transitionTo...State()} so no test depends on a clock, except the one that proves the
 * breaker recovers by itself.
 *
 * <p>Sections: A construction, B closed breaker passes everything through, C delegate failures, D opening,
 * E refusals while open, F recovery, G many threads, H together with the interceptor and the response filter.
 */
class CircuitBreakerIdempotencyStoreTests {

    private enum Op { CLAIM, FIND, COMPLETE, DELETE, EXISTS, REMAINING_TTL }

    private static final String KEY = "idempotency:v1:anonymous:k1";
    private static final IdempotencyRecord RECORD = new IdempotencyRecord(KEY, "fp", null, null, 0, Duration.ofMinutes(1));
    private static final String SECRET = "secret-host.internal:6379 password=hunter2";

    /** Stands in for any failure of the real store. */
    private static class StoreDown extends RuntimeException {
        StoreDown() {
            super("connection to " + SECRET + " refused");
        }
    }

    private IdempotencyStore delegate;
    private CircuitBreaker breaker;
    private CircuitBreakerIdempotencyStore store;

    @BeforeEach
    void setUp() {
        delegate = mock(IdempotencyStore.class);
        breaker = breaker(Duration.ofMinutes(10));
        store = new CircuitBreakerIdempotencyStore(delegate, breaker);
    }

    /** Window of 2 calls; opens only when both fail; stays open for {@code wait} unless forced. */
    private static CircuitBreaker breaker(Duration wait) {
        return CircuitBreaker.of("test", config(wait).build());
    }

    private static CircuitBreakerConfig.Builder config(Duration wait) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(100)
                .permittedNumberOfCallsInHalfOpenState(1)
                .waitDurationInOpenState(wait);
    }

    /** Calls one operation with the fixed arguments (or with nulls). */
    private static Object call(IdempotencyStore target, Op op, boolean nullArgs) {
        IdempotencyRecord record = nullArgs ? null : RECORD;
        String key = nullArgs ? null : KEY;
        return switch (op) {
            case CLAIM -> target.claim(record);
            case FIND -> target.find(key);
            case COMPLETE -> target.complete(record);
            case DELETE -> target.delete(key);
            case EXISTS -> target.exists(key);
            case REMAINING_TTL -> target.getRemainingTtl(key);
        };
    }

    private Object call(Op op) {
        return call(store, op, false);
    }

    private void stubReturns(Op op, Object value) {
        switch (op) {
            case CLAIM -> when(delegate.claim(any())).thenReturn((Boolean) value);
            case FIND -> when(delegate.find(any())).thenReturn(cast(value));
            case COMPLETE -> when(delegate.complete(any())).thenReturn((Boolean) value);
            case DELETE -> when(delegate.delete(any())).thenReturn((Boolean) value);
            case EXISTS -> when(delegate.exists(any())).thenReturn((Boolean) value);
            case REMAINING_TTL -> when(delegate.getRemainingTtl(any())).thenReturn(cast(value));
        }
    }

    private void stubThrows(Op op, Throwable failure) {
        switch (op) {
            case CLAIM -> when(delegate.claim(any())).thenThrow(failure);
            case FIND -> when(delegate.find(any())).thenThrow(failure);
            case COMPLETE -> when(delegate.complete(any())).thenThrow(failure);
            case DELETE -> when(delegate.delete(any())).thenThrow(failure);
            case EXISTS -> when(delegate.exists(any())).thenThrow(failure);
            case REMAINING_TTL -> when(delegate.getRemainingTtl(any())).thenThrow(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    private void stubAllThrow(Throwable failure) {
        for (Op op : Op.values()) stubThrows(op, failure);
    }

    /** The delegate saw exactly one call, to this operation, with these arguments, and nothing else. */
    private void verifyOnlyCall(Op op, boolean nullArgs) {
        IdempotencyRecord record = nullArgs ? null : RECORD;
        String key = nullArgs ? null : KEY;
        switch (op) {
            case CLAIM -> verify(delegate, times(1)).claim(nullArgs ? isNull() : same(record));
            case FIND -> verify(delegate, times(1)).find(key);
            case COMPLETE -> verify(delegate, times(1)).complete(nullArgs ? isNull() : same(record));
            case DELETE -> verify(delegate, times(1)).delete(key);
            case EXISTS -> verify(delegate, times(1)).exists(key);
            case REMAINING_TTL -> verify(delegate, times(1)).getRemainingTtl(key);
        }
        verifyNoMoreInteractions(delegate);
    }

    /** Makes the delegate fail twice, which opens the breaker. Afterwards the mock is reset: no stubs, no calls. */
    private void trip() {
        stubAllThrow(new StoreDown());
        assertThrows(StoreDown.class, () -> call(Op.CLAIM));
        assertThrows(StoreDown.class, () -> call(Op.CLAIM));
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState(), "two failures must open this breaker");
        reset(delegate);
    }

    private static Stream<Arguments> everyOp() {
        return Stream.of(Op.values()).map(Arguments::of);
    }

    /** Every operation paired with each of its two kinds of normal answer (true/false, present/empty). */
    private static Stream<Arguments> normalAnswers() {
        return Stream.of(
                Arguments.of(Op.CLAIM, true), Arguments.of(Op.CLAIM, false),
                Arguments.of(Op.COMPLETE, true), Arguments.of(Op.COMPLETE, false),
                Arguments.of(Op.DELETE, true), Arguments.of(Op.DELETE, false),
                Arguments.of(Op.EXISTS, true), Arguments.of(Op.EXISTS, false),
                Arguments.of(Op.FIND, Optional.of(RECORD)), Arguments.of(Op.FIND, Optional.empty()),
                Arguments.of(Op.REMAINING_TTL, Optional.of(Duration.ofSeconds(42))),
                Arguments.of(Op.REMAINING_TTL, Optional.empty()));
    }

    private static Stream<Arguments> everyPairOfOps() {
        List<Arguments> pairs = new ArrayList<>();
        for (Op first : Op.values()) for (Op second : Op.values()) pairs.add(Arguments.of(first, second));
        return pairs.stream();
    }

    @Test
    void aNullDelegateIsRejected() {
        assertThrows(NullPointerException.class, () -> new CircuitBreakerIdempotencyStore(null, breaker));
    }

    @Test
    void aNullBreakerIsRejected() {
        assertThrows(NullPointerException.class, () -> new CircuitBreakerIdempotencyStore(delegate, null));
    }

    @Test
    void constructionTouchesNeitherTheDelegateNorTheBreaker() {
        CircuitBreaker fresh = breaker(Duration.ofMinutes(1));

        new CircuitBreakerIdempotencyStore(delegate, fresh);

        verifyNoInteractions(delegate);
        assertEquals(CircuitBreaker.State.CLOSED, fresh.getState());
        assertEquals(0, fresh.getMetrics().getNumberOfBufferedCalls());
    }

    @Test
    void constructionAcceptsABreakerThatIsAlreadyOpenAndThenRefusesCalls() {
        CircuitBreaker open = breaker(Duration.ofMinutes(10));
        open.transitionToOpenState();
        CircuitBreakerIdempotencyStore wrapped = new CircuitBreakerIdempotencyStore(delegate, open);

        assertThrows(StoreUnavailableException.class, () -> wrapped.claim(RECORD));
        verifyNoInteractions(delegate);
    }

    @ParameterizedTest(name = "{0} returns the delegate''s answer {1}")
    @MethodSource("normalAnswers")
    void theDelegatesAnswerComesBackUnchanged(Op op, Object answer) {
        stubReturns(op, answer);

        Object result = call(op);

        if (answer instanceof Optional) {
            assertSame(answer, result, "the very same Optional, not a copy");
        } else {
            assertEquals(answer, result);
        }
    }

    @ParameterizedTest(name = "{0} makes exactly one delegate call with the same arguments")
    @MethodSource("everyOp")
    void argumentsReachTheDelegateUnchangedAndOnlyOnce(Op op) {
        stubReturns(op, defaultAnswer(op));

        call(op);

        verifyOnlyCall(op, false);
    }

    @ParameterizedTest(name = "{0} passes null straight through")
    @MethodSource("everyOp")
    void nullArgumentsAreTheDelegatesBusinessNotTheDecoratorsEither(Op op) {
        stubReturns(op, defaultAnswer(op));

        call(store, op, true);

        verifyOnlyCall(op, true);
    }

    @ParameterizedTest(name = "{0} records one success")
    @MethodSource("everyOp")
    void aSuccessfulCallIsRecordedOnTheBreaker(Op op) {
        stubReturns(op, defaultAnswer(op));

        call(op);

        assertEquals(1, breaker.getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls());
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    }

    @ParameterizedTest(name = "{0}: false and empty answers are not failures")
    @MethodSource("everyOp")
    void aNegativeAnswerIsNeverCountedAsAFailure(Op op) {
        stubReturns(op, negativeAnswer(op));

        for (int i = 0; i < 50; i++) call(op);

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls());
    }

    @Test
    void theDecoratorIsAnIdempotencyStore() {
        assertInstanceOf(IdempotencyStore.class, store);
    }

    private static Object defaultAnswer(Op op) {
        return switch (op) {
            case CLAIM, COMPLETE, DELETE, EXISTS -> true;
            case FIND, REMAINING_TTL -> op == Op.FIND ? Optional.of(RECORD) : Optional.of(Duration.ofSeconds(1));
        };
    }

    private static Object negativeAnswer(Op op) {
        return switch (op) {
            case CLAIM, COMPLETE, DELETE, EXISTS -> false;
            case FIND, REMAINING_TTL -> Optional.empty();
        };
    }

    @ParameterizedTest(name = "{0}: the delegate''s exception is rethrown as it was")
    @MethodSource("everyOp")
    void theDelegatesExceptionIsRethrownUnchanged(Op op) {
        StoreDown failure = new StoreDown();
        stubThrows(op, failure);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> call(op));

        assertSame(failure, thrown, "same instance: not wrapped, not replaced");
    }

    @ParameterizedTest(name = "{0}: a failing call is not retried")
    @MethodSource("everyOp")
    void aFailingCallIsAttemptedOnce(Op op) {
        stubThrows(op, new StoreDown());

        assertThrows(StoreDown.class, () -> call(op));

        verifyOnlyCall(op, false);
    }

    @ParameterizedTest(name = "{0}: a failure is recorded")
    @MethodSource("everyOp")
    void aFailureIsRecordedOnTheBreaker(Op op) {
        stubThrows(op, new StoreDown());

        assertThrows(StoreDown.class, () -> call(op));

        assertEquals(1, breaker.getMetrics().getNumberOfFailedCalls());
        assertEquals(0, breaker.getMetrics().getNumberOfSuccessfulCalls());
    }

    @ParameterizedTest(name = "{0}: one failure does not open the breaker")
    @MethodSource("everyOp")
    void aSingleFailureLeavesTheBreakerClosedAndTheNextCallStillReachesTheDelegate(Op op) {
        stubThrows(op, new StoreDown());
        assertThrows(StoreDown.class, () -> call(op));
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());

        reset(delegate);
        stubReturns(op, defaultAnswer(op));
        assertNotNull(call(op));

        verifyOnlyCall(op, false);
    }

    @ParameterizedTest(name = "{0}: an Error passes through untouched")
    @MethodSource("everyOp")
    void anErrorIsNotWrappedEither(Op op) {
        AssertionError failure = new AssertionError("out of memory, say");
        stubThrows(op, failure);

        AssertionError thrown = assertThrows(AssertionError.class, () -> call(op));

        assertSame(failure, thrown);
    }

    @Test
    void aFailureOfOneKindIsRethrownAsThatKind() {
        stubThrows(Op.FIND, new IllegalStateException("x"));

        assertThrows(IllegalStateException.class, () -> store.find(KEY));
    }

    @Test
    void theDecoratorDoesNotDecideWhatCountsAsAFailureTheBreakersConfigurationDoes() {
        CircuitBreaker lenient = CircuitBreaker.of("lenient",
                config(Duration.ofMinutes(10)).ignoreExceptions(IllegalArgumentException.class).build());
        CircuitBreakerIdempotencyStore wrapped = new CircuitBreakerIdempotencyStore(delegate, lenient);
        IllegalArgumentException ignored = new IllegalArgumentException("caller bug, not a store outage");
        when(delegate.claim(any())).thenThrow(ignored);

        for (int i = 0; i < 10; i++) {
            assertSame(ignored, assertThrows(IllegalArgumentException.class, () -> wrapped.claim(RECORD)));
        }

        assertEquals(CircuitBreaker.State.CLOSED, lenient.getState());
        assertEquals(0, lenient.getMetrics().getNumberOfFailedCalls());
        verify(delegate, times(10)).claim(any());
    }

    @Test
    void aBreakerThatRecordsOnlySomeExceptionsStillOpensOnThose() {
        CircuitBreaker picky = CircuitBreaker.of("picky",
                config(Duration.ofMinutes(10)).recordExceptions(StoreDown.class).ignoreExceptions(IllegalStateException.class).build());
        CircuitBreakerIdempotencyStore wrapped = new CircuitBreakerIdempotencyStore(delegate, picky);
        when(delegate.claim(any())).thenThrow(new StoreDown());

        assertThrows(StoreDown.class, () -> wrapped.claim(RECORD));
        assertThrows(StoreDown.class, () -> wrapped.claim(RECORD));

        assertEquals(CircuitBreaker.State.OPEN, picky.getState());
    }

    @ParameterizedTest(name = "failures of {0} then {1} open the breaker")
    @MethodSource("everyPairOfOps")
    void failuresOfAnyTwoOperationsOpenTheSharedBreaker(Op first, Op second) {
        stubAllThrow(new StoreDown());

        assertThrows(StoreDown.class, () -> call(first));
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertThrows(StoreDown.class, () -> call(second));

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
    }

    @Test
    void aSuccessBetweenFailuresKeepsTheBreakerClosed() {
        stubReturns(Op.CLAIM, true);
        stubThrows(Op.FIND, new StoreDown());

        call(Op.CLAIM);
        assertThrows(StoreDown.class, () -> call(Op.FIND));
        call(Op.CLAIM);
        assertThrows(StoreDown.class, () -> call(Op.FIND));
        call(Op.CLAIM);

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState(), "never two failures in a row");
        verify(delegate, times(3)).claim(any());
        verify(delegate, times(2)).find(any());
    }

    @Test
    void theCallThatOpensTheBreakerStillThrowsTheDelegatesOwnException() {
        StoreDown first = new StoreDown();
        StoreDown second = new StoreDown();
        when(delegate.claim(any())).thenThrow(first, second);

        assertSame(first, assertThrows(StoreDown.class, () -> call(Op.CLAIM)));
        assertSame(second, assertThrows(StoreDown.class, () -> call(Op.CLAIM)));

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
    }

    @ParameterizedTest(name = "{0} is refused without touching the delegate")
    @MethodSource("everyOp")
    void anOpenBreakerRefusesEveryOperationWithoutCallingTheDelegate(Op op) {
        trip();

        assertThrows(StoreUnavailableException.class, () -> call(op));

        verifyNoInteractions(delegate);
    }

    @ParameterizedTest(name = "{0} is refused when the breaker was forced open")
    @MethodSource("everyOp")
    void aBreakerOpenedByHandIsRespectedToo(Op op) {
        breaker.transitionToOpenState();

        assertThrows(StoreUnavailableException.class, () -> call(op));

        verifyNoInteractions(delegate);
    }

    @Test
    void theRefusalIsARuntimeExceptionSoExistingCatchBlocksStillWork() {
        trip();

        Throwable thrown = assertThrows(Throwable.class, () -> call(Op.CLAIM));

        assertInstanceOf(RuntimeException.class, thrown);
    }

    @Test
    void theRefusalCarriesTheBreakersReasonAsItsCause() {
        trip();

        StoreUnavailableException thrown = assertThrows(StoreUnavailableException.class, () -> call(Op.FIND));

        assertInstanceOf(CallNotPermittedException.class, thrown.getCause());
    }

    @Test
    void theRefusalNeverRepeatsWhatTheStoreSaidWhenItFailed() {
        trip();

        StoreUnavailableException thrown = assertThrows(StoreUnavailableException.class, () -> call(Op.CLAIM));

        assertNotNull(thrown.getMessage());
        assertFalse(thrown.getMessage().isBlank());
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            String message = String.valueOf(t.getMessage());
            assertFalse(message.contains("secret-host"), "host leaked: " + message);
            assertFalse(message.contains("hunter2"), "password leaked: " + message);
            assertFalse(message.contains("6379"), "port leaked: " + message);
        }
    }

    @Test
    void refusalsAreCountedAsNotPermittedAndNeverAsFailures() {
        trip();
        int failuresBefore = breaker.getMetrics().getNumberOfFailedCalls();

        for (int i = 0; i < 5; i++) {
            assertThrows(StoreUnavailableException.class, () -> call(Op.CLAIM));
        }

        assertEquals(5, breaker.getMetrics().getNumberOfNotPermittedCalls());
        assertEquals(failuresBefore, breaker.getMetrics().getNumberOfFailedCalls());
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
    }

    @Test
    void manyRefusalsInARowNeverReachTheDelegate() {
        trip();

        for (int i = 0; i < 200; i++) {
            Op op = Op.values()[i % Op.values().length];
            assertThrows(StoreUnavailableException.class, () -> call(op));
        }

        verifyNoInteractions(delegate);
    }

    @Test
    void whileTheWaitHasNotPassedTheBreakerStaysOpen() throws Exception {
        trip();
        Thread.sleep(50);

        assertThrows(StoreUnavailableException.class, () -> call(Op.CLAIM));

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        verifyNoInteractions(delegate);
    }

    @ParameterizedTest(name = "{0}: a good probe closes the breaker")
    @MethodSource("everyOp")
    void aSuccessfulProbeInHalfOpenClosesTheBreakerAndReturnsTheAnswer(Op op) {
        trip();
        breaker.transitionToHalfOpenState();
        stubReturns(op, defaultAnswer(op));

        Object result = call(op);

        assertEquals(defaultAnswer(op), result);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        verifyOnlyCall(op, false);
    }

    @Test
    void afterRecoveryCallsFlowAgain() {
        trip();
        breaker.transitionToHalfOpenState();
        reset(delegate);
        when(delegate.claim(any())).thenReturn(true);
        call(Op.CLAIM);

        for (int i = 0; i < 20; i++) assertTrue((Boolean) call(Op.CLAIM));

        verify(delegate, times(21)).claim(any());
    }

    @ParameterizedTest(name = "{0}: a failed probe opens the breaker again")
    @MethodSource("everyOp")
    void aFailedProbeInHalfOpenReopensTheBreakerAndThrowsTheDelegatesException(Op op) {
        trip();
        breaker.transitionToHalfOpenState();
        StoreDown stillDown = new StoreDown();
        stubThrows(op, stillDown);

        assertSame(stillDown, assertThrows(StoreDown.class, () -> call(op)));

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        clearInvocations(delegate);
        assertThrows(StoreUnavailableException.class, () -> call(op));
        verifyNoInteractions(delegate);
    }

    @Test
    void aProbeThatAnswersFalseOrEmptyCountsAsRecovery() {
        trip();
        breaker.transitionToHalfOpenState();
        when(delegate.claim(any())).thenReturn(false);

        assertFalse((Boolean) call(Op.CLAIM));

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    void whileAProbeIsRunningOtherCallsAreRefusedWithoutReachingTheDelegate() throws Exception {
        trip();
        breaker.transitionToHalfOpenState();
        CountDownLatch probeInside = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        when(delegate.claim(any())).thenAnswer(invocation -> {
            probeInside.countDown();
            assertTrue(releaseProbe.await(10, TimeUnit.SECONDS));
            return true;
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> probe = pool.submit(() -> call(Op.CLAIM));
            assertTrue(probeInside.await(10, TimeUnit.SECONDS), "the probe never reached the delegate");

            assertThrows(StoreUnavailableException.class, () -> call(Op.FIND));
            assertThrows(StoreUnavailableException.class, () -> call(Op.CLAIM));
            verify(delegate, never()).find(any());
            verify(delegate, times(1)).claim(any());

            releaseProbe.countDown();
            assertEquals(true, probe.get(10, TimeUnit.SECONDS));
            assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        } finally {
            releaseProbe.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void theBreakerRecoversByItselfOnceItsWaitHasPassed() throws Exception {
        CircuitBreaker quick = breaker(Duration.ofMillis(100));
        CircuitBreakerIdempotencyStore wrapped = new CircuitBreakerIdempotencyStore(delegate, quick);
        when(delegate.claim(any())).thenThrow(new StoreDown());
        assertThrows(StoreDown.class, () -> wrapped.claim(RECORD));
        assertThrows(StoreDown.class, () -> wrapped.claim(RECORD));
        assertEquals(CircuitBreaker.State.OPEN, quick.getState());
        assertThrows(StoreUnavailableException.class, () -> wrapped.claim(RECORD));

        reset(delegate);
        when(delegate.claim(any())).thenReturn(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean recovered = false;
        while (System.nanoTime() < deadline && !recovered) {
            try {
                recovered = wrapped.claim(RECORD);
            } catch (StoreUnavailableException stillOpen) {
                Thread.sleep(20);
            }
        }

        assertTrue(recovered, "the breaker never let a probe through after its wait time");
        assertEquals(CircuitBreaker.State.CLOSED, quick.getState());
    }

    @Test
    void manyThreadsOnAHealthyStoreEachGetOneDelegateCallAndTheRightAnswer() throws Exception {
        IdempotencyInterceptorTests.FakeStore fake = new IdempotencyInterceptorTests.FakeStore();
        CircuitBreakerIdempotencyStore wrapped = new CircuitBreakerIdempotencyStore(fake, breaker(Duration.ofMinutes(1)));
        int threads = 16;
        int perThread = 100;
        AtomicInteger wins = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        IdempotencyRecord record = new IdempotencyRecord(
                                "key-" + id + "-" + i, "fp", null, null, 0, Duration.ofMinutes(1));
                        if (wrapped.claim(record)) wins.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads * perThread, fake.claims.get(), "one delegate call per decorator call");
        assertEquals(threads * perThread, wins.get(), "every key was new, so every claim wins");
    }

    @Test
    void manyThreadsAgainstAnOpenBreakerAreAllRefusedAndTheDelegateStaysUntouched() throws Exception {
        IdempotencyInterceptorTests.FakeStore fake = new IdempotencyInterceptorTests.FakeStore();
        fake.claimFailure = new StoreDown();
        CircuitBreakerIdempotencyStore wrapped = new CircuitBreakerIdempotencyStore(fake, breaker(Duration.ofMinutes(10)));
        assertThrows(StoreDown.class, () -> wrapped.claim(RECORD));
        assertThrows(StoreDown.class, () -> wrapped.claim(RECORD));
        int callsWhenOpened = fake.claims.get();
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 50; i++) {
                        try {
                            wrapped.claim(RECORD);
                            other.incrementAndGet();
                        } catch (StoreUnavailableException e) {
                            refused.incrementAndGet();
                        } catch (RuntimeException e) {
                            other.incrementAndGet();
                        }
                    }
                }));
            }
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(800, refused.get());
        assertEquals(0, other.get());
        assertEquals(callsWhenOpened, fake.claims.get(), "an open breaker must not let any call through");
    }

    private HttpPost interceptorOver(IdempotencyInterceptorTests.FakeStore fake, CircuitBreaker withBreaker) {
        return new HttpPost(new IdempotencyInterceptor(new CircuitBreakerIdempotencyStore(fake, withBreaker),
                new Fingerprint(), new ProblemResponseWriter(JsonMapper.builder().build())));
    }

    /** Sends a POST through the interceptor the way the filter would and remembers the response. */
    private static final class HttpPost {
        final IdempotencyInterceptor interceptor;
        MockHttpServletResponse response;

        HttpPost(IdempotencyInterceptor interceptor) {
            this.interceptor = interceptor;
        }

        boolean send(String key) throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
            request.setContentType("application/json");
            request.setContent("{\"amount\":1}".getBytes(StandardCharsets.UTF_8));
            request.addHeader("Idempotency-Key", key);
            response = new MockHttpServletResponse();
            HandlerMethod handler = new HandlerMethod(new IdempotencyInterceptorTests.Controller(), "create");
            return interceptor.preHandle(new RequestWrapper(request), response, handler);
        }
    }

    @Test
    void theInterceptorAnswers503WhileTheStoreFailsAndWhileTheBreakerIsOpen() throws Exception {
        IdempotencyInterceptorTests.FakeStore fake = new IdempotencyInterceptorTests.FakeStore();
        fake.claimFailure = new StoreDown();
        CircuitBreaker withBreaker = breaker(Duration.ofMinutes(10));
        HttpPost http = interceptorOver(fake, withBreaker);

        for (int i = 0; i < 2; i++) {
            assertFalse(http.send("k" + i));
            assertEquals(503, http.response.getStatus());
        }
        assertEquals(CircuitBreaker.State.OPEN, withBreaker.getState());
        int storeCallsWhenOpened = fake.claims.get();

        assertFalse(http.send("k3"));

        assertEquals(503, http.response.getStatus(), "an open breaker looks the same to the client");
        assertEquals(String.valueOf(IdempotencyDefaults.STORE_RETRY_AFTER.toSeconds()),
                http.response.getHeader("Retry-After"));
        assertEquals(storeCallsWhenOpened, fake.claims.get(), "the store must not be called while open");
        assertFalse(http.response.getContentAsString().contains("hunter2"));
        assertFalse(http.response.getContentAsString().contains("secret-host"));
    }

    @Test
    void theInterceptorWorksAgainWhenTheStoreComesBack() throws Exception {
        IdempotencyInterceptorTests.FakeStore fake = new IdempotencyInterceptorTests.FakeStore();
        fake.claimFailure = new StoreDown();
        CircuitBreaker withBreaker = breaker(Duration.ofMinutes(10));
        HttpPost http = interceptorOver(fake, withBreaker);
        http.send("k1");
        http.send("k2");
        assertEquals(CircuitBreaker.State.OPEN, withBreaker.getState());

        fake.claimFailure = null;
        withBreaker.transitionToHalfOpenState();

        assertTrue(http.send("k3"), "the controller may run again");
        assertEquals(CircuitBreaker.State.CLOSED, withBreaker.getState());
    }

    @Test
    void aLostClaimIsNotTreatedAsAFailureByTheInterceptorEither() throws Exception {
        IdempotencyInterceptorTests.FakeStore fake = new IdempotencyInterceptorTests.FakeStore();
        CircuitBreaker withBreaker = breaker(Duration.ofMinutes(10));
        HttpPost http = interceptorOver(fake, withBreaker);
        assertTrue(http.send("same"));

        for (int i = 0; i < 10; i++) {
            assertFalse(http.send("same"));
            assertEquals(409, http.response.getStatus());
        }

        assertEquals(CircuitBreaker.State.CLOSED, withBreaker.getState(), "409s are normal answers, not outages");
    }

    @Test
    void theResponseFilterStillDeliversTheResponseWhenTheBreakerIsOpen() throws Exception {
        IdempotencyInterceptorTests.FakeStore fake = new IdempotencyInterceptorTests.FakeStore();
        CircuitBreaker withBreaker = breaker(Duration.ofMinutes(10));
        withBreaker.transitionToOpenState();
        ResponseCaptureFilter filter = new ResponseCaptureFilter(new CircuitBreakerIdempotencyStore(fake, withBreaker));
        IdempotencyRecord claim = new IdempotencyRecord(KEY, "fp", null, null, 0, Duration.ofMinutes(1));
        MockHttpServletResponse real = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("POST", "/orders"), real, (request, response) -> {
            request.setAttribute(IdempotencyDefaults.CLAIM_ATTRIBUTE, claim);
            WebUtils.getNativeResponse(response, CapturingResponse.class).startCapturing();
            response.getOutputStream().write("created".getBytes(StandardCharsets.UTF_8));
        });

        assertEquals("created", real.getContentAsString(), "the client must still get its response");
        assertEquals(0, fake.completes.get() + fake.deletes.get(), "nothing reaches the store while open");
    }
}
