package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.autoconfigure.IdempotencyAutoConfiguration;
import io.github.lowvisjacket.idempotency.autoconfigure.IdempotencyCircuitBreakerAutoConfiguration;
import io.github.lowvisjacket.idempotency.autoconfigure.IdempotencyStoreAutoConfiguration;
import io.github.lowvisjacket.idempotency.exception.StoreUnavailableException;
import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;
import io.github.lowvisjacket.idempotency.store.CircuitBreakerIdempotencyStore;
import io.github.lowvisjacket.idempotency.store.IdempotencyStore;
import io.github.lowvisjacket.idempotency.store.RedisIdempotencyStore;
import io.github.lowvisjacket.idempotency.web.ResponseCaptureFilter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the circuit breaker is wired around the store. The store is a mock: only the wiring is tested.
 */
class IdempotencyCircuitBreakerAutoConfigurationTests {

    private static final String BREAKER_BEAN = "idempotencyCircuitBreaker";

    private static final String[] TIGHT = {
            "idempotency.circuit-breaker.sliding-window-size=2",
            "idempotency.circuit-breaker.minimum-number-of-calls=2",
            "idempotency.circuit-breaker.failure-rate-threshold=100",
            "idempotency.circuit-breaker.wait-duration-in-open-state=10m",
            "idempotency.circuit-breaker.permitted-calls-in-half-open-state=1"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    IdempotencyStoreAutoConfiguration.class, IdempotencyCircuitBreakerAutoConfiguration.class));

    private static IdempotencyRecord record() {
        return new IdempotencyRecord("k", "f", null, null, 0, Duration.ofMinutes(1));
    }

    private static CircuitBreaker breakerOf(AssertableApplicationContext context) {
        return context.getBean(BREAKER_BEAN, CircuitBreaker.class);
    }

    @Test
    void wrapsTheRedisStoreItCreates() {
        runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IdempotencyStore.class);
            assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(CircuitBreakerIdempotencyStore.class);
        });
    }

    @Test
    void wrapsAStoreTheApplicationDefinedItself() {
        IdempotencyStore mine = mock(IdempotencyStore.class);
        runner.withBean("myStore", IdempotencyStore.class, () -> mine).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IdempotencyStore.class);
            assertThat(context.getBean("myStore")).isInstanceOf(CircuitBreakerIdempotencyStore.class);
            assertThat(context.getBean("myStore")).isNotSameAs(mine);
        });
    }

    @Test
    void theWrappedStoreFetchedByTypeAndByNameIsTheSameObject() {
        runner.withBean("myStore", IdempotencyStore.class, () -> mock(IdempotencyStore.class)).run(context ->
                assertThat(context.getBean(IdempotencyStore.class)).isSameAs(context.getBean("myStore")));
    }

    @Test
    void callsReachTheRealStoreAndItsAnswersComeBack() {
        IdempotencyStore mine = mock(IdempotencyStore.class);
        IdempotencyRecord r = record();
        when(mine.claim(r)).thenReturn(true);
        when(mine.exists("k")).thenReturn(true);
        runner.withBean("myStore", IdempotencyStore.class, () -> mine).run(context -> {
            IdempotencyStore store = context.getBean(IdempotencyStore.class);
            assertThat(store.claim(r)).isTrue();
            assertThat(store.exists("k")).isTrue();
            verify(mine).claim(r);
            verify(mine).exists("k");
        });
    }

    @Test
    void callsAreRecordedOnTheBreakerBean() {
        IdempotencyStore mine = mock(IdempotencyStore.class);
        runner.withBean("myStore", IdempotencyStore.class, () -> mine).run(context -> {
            context.getBean(IdempotencyStore.class).exists("k");
            assertThat(breakerOf(context).getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
        });
    }

    @Test
    void failuresOpenTheBreakerAfterTheConfiguredNumberOfCallsAndThenTheStoreIsNotCalled() {
        IdempotencyStore mine = mock(IdempotencyStore.class);
        when(mine.exists(any())).thenThrow(new IllegalStateException("redis down"));
        runner.withPropertyValues(TIGHT).withBean("myStore", IdempotencyStore.class, () -> mine).run(context -> {
            IdempotencyStore store = context.getBean(IdempotencyStore.class);
            assertThatThrownBy(() -> store.exists("k")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.exists("k")).isInstanceOf(IllegalStateException.class);
            assertThat(breakerOf(context).getState()).isEqualTo(CircuitBreaker.State.OPEN);

            assertThatThrownBy(() -> store.exists("k")).isInstanceOf(StoreUnavailableException.class);
            verify(mine, times(2)).exists("k");
        });
    }

    @Test
    void theBreakerGetsItsValuesFromTheProperties() {
        runner.withPropertyValues(
                "idempotency.circuit-breaker.sliding-window-size=8",
                "idempotency.circuit-breaker.minimum-number-of-calls=4",
                "idempotency.circuit-breaker.failure-rate-threshold=75",
                "idempotency.circuit-breaker.wait-duration-in-open-state=2m",
                "idempotency.circuit-breaker.permitted-calls-in-half-open-state=5")
                .withBean("myStore", IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> {
                    CircuitBreakerConfig c = breakerOf(context).getCircuitBreakerConfig();
                    assertThat(c.getSlidingWindowSize()).isEqualTo(8);
                    assertThat(c.getMinimumNumberOfCalls()).isEqualTo(4);
                    assertThat(c.getFailureRateThreshold()).isEqualTo(75f);
                    assertThat(c.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(Duration.ofMinutes(2).toMillis());
                    assertThat(c.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(5);
                    assertThat(c.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
                });
    }

    @Test
    void theBreakerUsesTheDefaultsWhenNothingIsSet() {
        runner.withBean("myStore", IdempotencyStore.class, () -> mock(IdempotencyStore.class)).run(context -> {
            CircuitBreakerConfig c = breakerOf(context).getCircuitBreakerConfig();
            assertThat(c.getSlidingWindowSize()).isEqualTo(20);
            assertThat(c.getMinimumNumberOfCalls()).isEqualTo(10);
            assertThat(c.getFailureRateThreshold()).isEqualTo(50f);
            assertThat(c.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        });
    }

    @Test
    void theBreakerIsNamedIdempotency() {
        runner.withBean("myStore", IdempotencyStore.class, () -> mock(IdempotencyStore.class)).run(context ->
                assertThat(breakerOf(context).getName()).isEqualTo("idempotency"));
    }

    @Test
    void thereIsExactlyOneBreakerBean() {
        runner.withBean("myStore", IdempotencyStore.class, () -> mock(IdempotencyStore.class)).run(context ->
                assertThat(context).hasSingleBean(CircuitBreaker.class));
    }

    @Test
    void anApplicationBreakerWithThatNameWins() {
        CircuitBreaker mine = CircuitBreaker.ofDefaults("mine");
        runner.withBean(BREAKER_BEAN, CircuitBreaker.class, () -> mine)
                .withBean("myStore", IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(CircuitBreaker.class);
                    assertThat(breakerOf(context)).isSameAs(mine);
                    context.getBean(IdempotencyStore.class).exists("k");
                    assertThat(mine.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
                });
    }

    @Test
    void switchedOffByPropertyTheStoreIsUsedAsItIsAndNoBreakerExists() {
        IdempotencyStore mine = mock(IdempotencyStore.class);
        runner.withPropertyValues("idempotency.circuit-breaker.enabled=false")
                .withBean("myStore", IdempotencyStore.class, () -> mine).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IdempotencyStore.class)).isSameAs(mine);
                    assertThat(context).doesNotHaveBean(CircuitBreaker.class);
                });
    }

    @Test
    void switchedOffByPropertyTheRedisStoreIsNotWrapped() {
        runner.withPropertyValues("idempotency.circuit-breaker.enabled=false")
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class)).run(context ->
                        assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(RedisIdempotencyStore.class));
    }

    @Test
    void switchedOnExplicitlyItWraps() {
        runner.withPropertyValues("idempotency.circuit-breaker.enabled=true")
                .withBean("myStore", IdempotencyStore.class, () -> mock(IdempotencyStore.class)).run(context ->
                        assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(CircuitBreakerIdempotencyStore.class));
    }

    @Test
    void theLibraryBeingSwitchedOffDoesNotStopTheBreakerWrapping() {
        runner.withPropertyValues("idempotency.enabled=false")
                .withBean("myStore", IdempotencyStore.class, () -> mock(IdempotencyStore.class)).run(context ->
                        assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(CircuitBreakerIdempotencyStore.class));
    }

    @Test
    void withoutAStoreNothingFailsAndNothingIsWrapped() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(IdempotencyStore.class);
        });
    }

    @Test
    void startsFineWhenResilience4jIsNotOnTheClasspath() {
        IdempotencyStore mine = mock(IdempotencyStore.class);
        runner.withClassLoader(new FilteredClassLoader(CircuitBreaker.class))
                .withBean("myStore", IdempotencyStore.class, () -> mine).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IdempotencyStore.class)).isSameAs(mine);
                });
    }

    @Test
    void startsFineWithRedisAndWithoutResilience4j() {
        runner.withClassLoader(new FilteredClassLoader(CircuitBreaker.class))
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class)).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(RedisIdempotencyStore.class);
                });
    }

    @Test
    void aStoreThatIsAlreadyABreakerIsNotWrappedAgain() {
        CircuitBreaker own = CircuitBreaker.ofDefaults("own");
        CircuitBreakerIdempotencyStore mine = new CircuitBreakerIdempotencyStore(mock(IdempotencyStore.class), own);
        runner.withBean("myStore", IdempotencyStore.class, () -> mine).run(context -> {
            assertThat(context.getBean(IdempotencyStore.class)).isSameAs(mine);
            context.getBean(IdempotencyStore.class).exists("k");
            assertThat(own.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
            assertThat(breakerOf(context).getMetrics().getNumberOfSuccessfulCalls())
                    .as("the context breaker must not have seen the call").isZero();
        });
    }

    @Test
    void otherBeansAreLeftAlone() {
        Object other = new Object();
        String text = "hello";
        runner.withBean("other", Object.class, () -> other).withBean("text", String.class, () -> text)
                .withBean("myStore", IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> {
                    assertThat(context.getBean("other")).isSameAs(other);
                    assertThat(context.getBean("text")).isSameAs(text);
                });
    }

    @Test
    void severalStoresAreAllWrapped() {
        runner.withBean("a", IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .withBean("b", IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> {
                    assertThat(context.getBean("a")).isInstanceOf(CircuitBreakerIdempotencyStore.class);
                    assertThat(context.getBean("b")).isInstanceOf(CircuitBreakerIdempotencyStore.class);
                });
    }

    @Test
    void aBadPropertyStopsTheApplicationAtStartup() {
        runner.withPropertyValues("idempotency.circuit-breaker.failure-rate-threshold=0")
                .withBean("myStore", IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("failure-rate-threshold");
                });
    }

    @Test
    void theContextClosesCleanlyAndWrappedStoresDoNotLeakTheMock() {
        IdempotencyStore mine = mock(IdempotencyStore.class);
        runner.withBean("myStore", IdempotencyStore.class, () -> mine).run(context -> {
            context.getBean(IdempotencyStore.class).delete("k");
            verify(mine).delete("k");
            verify(mine, never()).claim(any());
        });
    }

    @Test
    void theResponseFilterOfTheMainConfigurationGetsTheWrappedStore() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        IdempotencyStoreAutoConfiguration.class, IdempotencyCircuitBreakerAutoConfiguration.class,
                        IdempotencyAutoConfiguration.class))
                .withBean("myStore", IdempotencyStore.class, () -> mock(IdempotencyStore.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(IdempotencyStore.class);
                    ResponseCaptureFilter filter = context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                            .map(FilterRegistrationBean::getFilter)
                            .filter(ResponseCaptureFilter.class::isInstance)
                            .map(ResponseCaptureFilter.class::cast)
                            .findFirst().orElseThrow();
                    assertThat(ReflectionTestUtils.getField(filter, "store"))
                            .isInstanceOf(CircuitBreakerIdempotencyStore.class);
                });
    }

    @Test
    void itRunsAfterTheStoreConfigurationAndBeforeTheMainOne() {
        AutoConfiguration a = IdempotencyCircuitBreakerAutoConfiguration.class.getAnnotation(AutoConfiguration.class);
        assertThat(a).as("must be annotated with @AutoConfiguration").isNotNull();
        assertThat(Arrays.asList(a.after())).contains(IdempotencyStoreAutoConfiguration.class);
        assertThat(Arrays.asList(a.before())).contains(IdempotencyAutoConfiguration.class);
    }

    @Test
    void itIsListedForAutoDiscovery() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(
                "/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(in).isNotNull();
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .contains(IdempotencyCircuitBreakerAutoConfiguration.class.getName());
        }
    }
}
