package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.config.TtlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rules for TtlParser.parse (change a rule here on purpose if you disagree with it):
 *  - Format is a whole number immediately followed by ONE unit: s (seconds), m (minutes), h (hours), d (days).
 *  - Units are case-insensitive. Surrounding whitespace is ignored. Leading zeros are fine ("05m").
 *  - The result must be positive.
 *  - Digits must be plain ASCII 0-9 (Java would otherwise quietly accept digits from other scripts).
 *  - Anything else is an IllegalArgumentException whose message contains the bad text.
 *    (ISO-8601 such as "PT24H" and combinations such as "1h30m" are deliberately NOT supported.)
 */
class TtlParserTests {

    // --- Valid input ---

    @ParameterizedTest(name = "\"{0}\" parses to {1} seconds")
    @CsvSource({
            "1s,       1",
            "90s,      90",
            "1m,       60",
            "30m,      1800",
            "1h,       3600",
            "24h,      86400",
            "1d,       86400",
            "7d,       604800",
            "365d,     31536000"
    })
    void validValuesParseToTheRightDuration(String text, long expectedSeconds) {
        assertEquals(Duration.ofSeconds(expectedSeconds), TtlParser.parse(text));
    }

    @ParameterizedTest(name = "\"{0}\" is accepted")
    @ValueSource(strings = {"24H", "30M", "90S", "7D", "  24h", "24h  ", "  24h  ", "05m", "007s"})
    void caseWhitespaceAndLeadingZerosAreTolerated(String text) {
        assertDoesNotThrow(() -> TtlParser.parse(text));
    }

    @Test
    void uppercaseAndLowercaseGiveTheSameResult() {
        assertEquals(TtlParser.parse("24h"), TtlParser.parse("24H"));
    }

    @Test
    void leadingZerosDoNotChangeTheValue() {
        assertEquals(Duration.ofMinutes(5), TtlParser.parse("05m"));
    }

    @Test
    void theDefaultTtlConstantIsItselfValid() {
        assertEquals(Duration.ofHours(24), TtlParser.parse(IdempotencyDefaults.DEFAULT_TTL));
    }

    // --- Invalid input ---

    @ParameterizedTest(name = "[{index}] null / empty is rejected")
    @NullAndEmptySource
    void nullAndEmptyAreRejected(String text) {
        assertThrows(IllegalArgumentException.class, () -> TtlParser.parse(text));
    }

    @ParameterizedTest(name = "\"{0}\" is rejected")
    @ValueSource(strings = {
            " ", "   ",                // blank
            "24",                      // number without a unit
            "h", "d",                  // unit without a number
            "-5h", "+5h",              // signs
            "0s", "0h", "0d", "000m",  // zero is not a usable TTL
            "1.5h", "1,5h", ".5h",     // decimals
            "5w", "5y", "5ms", "5x",   // unsupported units
            "5hh", "5hm",              // extra characters
            "5 h", "5\th",             // space between number and unit
            "h5",                      // wrong order
            "24h30m", "1h 30m",        // combinations are not supported
            "PT24H", "P1D",            // ISO-8601 is not supported
            "1e3s",                    // scientific notation
            "five hours", "abc",       // words
            "0x10s",                   // hexadecimal
            "24h;", "24h!"             // junk after the unit
    })
    void malformedValuesAreRejected(String text) {
        assertThrows(IllegalArgumentException.class, () -> TtlParser.parse(text));
    }

    @Test
    void trailingNewlineIsTrimmedLikeOtherWhitespace() {
        assertEquals(Duration.ofHours(24), TtlParser.parse("24h\n"));
    }

    @ParameterizedTest(name = "\"{0}\" (non-ASCII digits) is rejected")
    @ValueSource(strings = {
            "٣h",          // ARABIC-INDIC DIGIT THREE
            "２４h",    // FULLWIDTH "24"
            "१d"           // DEVANAGARI DIGIT ONE
    })
    void nonAsciiDigitsAreRejected(String text) {
        assertThrows(IllegalArgumentException.class, () -> TtlParser.parse(text));
    }

    // --- Overflow: must be an IllegalArgumentException, not a leaked NumberFormat/Arithmetic exception ---

    @ParameterizedTest(name = "\"{0}\" is rejected without leaking another exception type")
    @ValueSource(strings = {
            "99999999999999999999d",       // too many digits for a long
            "9223372036854775807d",       // fits a long but overflows a Duration
            "9223372036854775807h"
    })
    void overflowingValuesAreRejectedCleanly(String text) {
        assertThrows(IllegalArgumentException.class, () -> TtlParser.parse(text));
    }

    @Test
    void theErrorMessageContainsTheBadText() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> TtlParser.parse("5w"));

        assertTrue(thrown.getMessage().contains("5w"), "message should help the developer find the bad value: " + thrown.getMessage());
    }

    @Test
    void parsingTheSameTextTwiceGivesEqualResults() {
        assertEquals(TtlParser.parse("30m"), TtlParser.parse("30m"));
    }

    // --- Added in review: the size limit should depend on the resulting time, not on the number alone ---

    @ParameterizedTest(name = "\"{0}\" equals {1} seconds")
    @CsvSource({
            "86400s,   86400",     // 24 hours written in seconds
            "10080m,   604800",    // 7 days written in minutes
            "168h,     604800",    // 7 days written in hours
            "100000s,  100000"
    })
    void bigNumbersInSmallUnitsAreAccepted(String text, long expectedSeconds) {
        assertEquals(Duration.ofSeconds(expectedSeconds), TtlParser.parse(text));
    }

    @Test
    void overflowMessageAlsoContainsTheBadText() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> TtlParser.parse("99999999999999999999d"));

        assertTrue(thrown.getMessage().contains("99999999999999999999d"), thrown.getMessage());
    }

    @Test
    void theMaximumIsExactlyThreeHundredSixtyFiveDays() {
        assertEquals(Duration.ofDays(365), TtlParser.parse("365d"));
        assertEquals(Duration.ofHours(8760), TtlParser.parse("8760h"));
        assertThrows(IllegalArgumentException.class, () -> TtlParser.parse("366d"));
        assertThrows(IllegalArgumentException.class, () -> TtlParser.parse("8761h"), "365 days and 1 hour is over the limit");
        assertThrows(IllegalArgumentException.class, () -> TtlParser.parse("525601m"), "365 days and 1 minute is over the limit");
    }
}
