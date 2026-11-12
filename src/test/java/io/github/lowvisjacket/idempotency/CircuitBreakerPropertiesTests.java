package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.autoconfigure.IdempotencyProperties;
import io.github.lowvisjacket.idempotency.autoconfigure.IdempotencyProperties.CircuitBreakerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code idempotency.circuit-breaker.*} settings, bound with Boot's own {@link Binder} (no Spring context).
 */
class CircuitBreakerPropertiesTests {

    private static final String P = "idempotency.circuit-breaker.";

    private static IdempotencyProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind(IdempotencyProperties.PREFIX, IdempotencyProperties.class)
                .orElseGet(IdempotencyProperties::new);
    }

    private static CircuitBreakerProperties cb(String name, String value) {
        return bind(Map.of(P + name, value)).getCircuitBreaker();
    }

    @Test
    void theGroupIsNeverNull() {
        assertNotNull(new IdempotencyProperties().getCircuitBreaker());
        assertNotNull(bind(Map.of()).getCircuitBreaker());
    }

    @Test
    void theGroupIsTheSameObjectEveryTime() {
        IdempotencyProperties p = new IdempotencyProperties();
        assertSame(p.getCircuitBreaker(), p.getCircuitBreaker());
    }

    @Test
    void defaultsWhenNothingIsConfigured() {
        CircuitBreakerProperties c = bind(Map.of()).getCircuitBreaker();
        assertTrue(c.isEnabled());
        assertEquals(20, c.getSlidingWindowSize());
        assertEquals(10, c.getMinimumNumberOfCalls());
        assertEquals(50, c.getFailureRateThreshold());
        assertEquals(Duration.ofSeconds(30), c.getWaitDurationInOpenState());
        assertEquals(3, c.getPermittedCallsInHalfOpenState());
    }

    @Test
    void defaultsAreSelfConsistent() {
        CircuitBreakerProperties c = new CircuitBreakerProperties();
        assertTrue(c.getMinimumNumberOfCalls() <= c.getSlidingWindowSize(),
                "the defaults must let the breaker ever decide: minimum calls cannot exceed the window");
    }

    @Test
    void settingTheBreakerDoesNotChangeTheOtherSettings() {
        IdempotencyProperties p = bind(Map.of(P + "enabled", "false", "idempotency.max-response-body-bytes", "99"));
        assertTrue(p.isEnabled());
        assertEquals(99, p.getMaxResponseBodyBytes());
        assertFalse(p.getCircuitBreaker().isEnabled());
    }

    @Test
    void switchingTheLibraryOffDoesNotSwitchTheBreakerSettingsOff() {
        IdempotencyProperties p = bind(Map.of("idempotency.enabled", "false"));
        assertFalse(p.isEnabled());
        assertTrue(p.getCircuitBreaker().isEnabled());
    }

    @Test
    void enabledCanBeSwitchedOff() {
        assertFalse(cb("enabled", "false").isEnabled());
    }

    @Test
    void everyValueCanBeChanged() {
        CircuitBreakerProperties c = bind(Map.of(
                P + "sliding-window-size", "8",
                P + "minimum-number-of-calls", "4",
                P + "failure-rate-threshold", "75",
                P + "wait-duration-in-open-state", "2m",
                P + "permitted-calls-in-half-open-state", "5")).getCircuitBreaker();
        assertEquals(8, c.getSlidingWindowSize());
        assertEquals(4, c.getMinimumNumberOfCalls());
        assertEquals(75, c.getFailureRateThreshold());
        assertEquals(Duration.ofMinutes(2), c.getWaitDurationInOpenState());
        assertEquals(5, c.getPermittedCallsInHalfOpenState());
    }

    @Test
    void changingOneValueLeavesTheOthersAtTheirDefaults() {
        CircuitBreakerProperties c = cb("failure-rate-threshold", "90");
        assertEquals(90, c.getFailureRateThreshold());
        assertEquals(20, c.getSlidingWindowSize());
        assertEquals(10, c.getMinimumNumberOfCalls());
        assertEquals(Duration.ofSeconds(30), c.getWaitDurationInOpenState());
        assertEquals(3, c.getPermittedCallsInHalfOpenState());
    }

    @Test
    void durationsUnderstandSimpleAndIsoText() {
        assertEquals(Duration.ofSeconds(45), cb("wait-duration-in-open-state", "45s").getWaitDurationInOpenState());
        assertEquals(Duration.ofMillis(1500), cb("wait-duration-in-open-state", "1500ms").getWaitDurationInOpenState());
        assertEquals(Duration.ofMinutes(1), cb("wait-duration-in-open-state", "PT1M").getWaitDurationInOpenState());
        assertEquals(Duration.ofHours(1), cb("wait-duration-in-open-state", "1h").getWaitDurationInOpenState());
    }

    @Test
    void namesBindRelaxed() {
        assertEquals(7, bind(Map.of("idempotency.circuitBreaker.slidingWindowSize", "7"))
                .getCircuitBreaker().getSlidingWindowSize());
    }

    @Test
    void anUnknownBreakerPropertyIsIgnoredNotFatal() {
        assertDoesNotThrow(() -> bind(Map.of(P + "no-such-thing", "1")));
    }

    @Test
    void notANumberFailsToBind() {
        assertThrows(BindException.class, () -> cb("sliding-window-size", "many"));
        assertThrows(BindException.class, () -> cb("failure-rate-threshold", "half"));
    }

    @Test
    void notADurationFailsToBind() {
        assertThrows(BindException.class, () -> cb("wait-duration-in-open-state", "soon"));
    }

    @Test
    void notABooleanFailsToBind() {
        assertThrows(BindException.class, () -> cb("enabled", "maybe"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-100"})
    void slidingWindowSizeMustBeAtLeastOne(String bad) {
        assertThrows(BindException.class, () -> cb("sliding-window-size", bad));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreakerProperties().setSlidingWindowSize(Integer.parseInt(bad)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void minimumNumberOfCallsMustBeAtLeastOne(String bad) {
        assertThrows(BindException.class, () -> cb("minimum-number-of-calls", bad));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreakerProperties().setMinimumNumberOfCalls(Integer.parseInt(bad)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "1000"})
    void failureRateMustBeFromOneToOneHundred(String bad) {
        assertThrows(BindException.class, () -> cb("failure-rate-threshold", bad));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreakerProperties().setFailureRateThreshold(Integer.parseInt(bad)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "50", "99", "100"})
    void failureRateEdgesAreAllowed(String good) {
        assertEquals(Integer.parseInt(good), cb("failure-rate-threshold", good).getFailureRateThreshold());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-5s", "-1ms"})
    void waitDurationMustBeLongerThanZero(String bad) {
        assertThrows(BindException.class, () -> cb("wait-duration-in-open-state", bad));
    }

    @Test
    void waitDurationSetterRejectsNullZeroAndNegative() {
        CircuitBreakerProperties c = new CircuitBreakerProperties();
        assertThrows(IllegalArgumentException.class, () -> c.setWaitDurationInOpenState(null));
        assertThrows(IllegalArgumentException.class, () -> c.setWaitDurationInOpenState(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> c.setWaitDurationInOpenState(Duration.ofMillis(-1)));
        assertDoesNotThrow(() -> c.setWaitDurationInOpenState(Duration.ofMillis(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void permittedCallsInHalfOpenMustBeAtLeastOne(String bad) {
        assertThrows(BindException.class, () -> cb("permitted-calls-in-half-open-state", bad));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreakerProperties().setPermittedCallsInHalfOpenState(Integer.parseInt(bad)));
    }

    @Test
    void aRejectedValueLeavesThePreviousValueInPlace() {
        CircuitBreakerProperties c = new CircuitBreakerProperties();
        c.setFailureRateThreshold(80);
        assertThrows(IllegalArgumentException.class, () -> c.setFailureRateThreshold(0));
        assertEquals(80, c.getFailureRateThreshold());
    }

    @Test
    void theErrorMessageNamesTheProblem() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CircuitBreakerProperties().setFailureRateThreshold(150));
        assertTrue(e.getMessage().contains("150"), "say which value was wrong: " + e.getMessage());
    }
}
