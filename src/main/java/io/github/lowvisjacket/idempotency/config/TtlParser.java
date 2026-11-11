package io.github.lowvisjacket.idempotency.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the text used in {@code @Idempotent(ttl = "24h")} into a {@link Duration}.
 *
 * <p>The format is a whole number followed by one unit: {@code s} (seconds), {@code m} (minutes),
 * {@code h} (hours) or {@code d} (days). Units are case-insensitive and surrounding whitespace is ignored.
 * Combinations such as {@code 1h30m} and ISO-8601 text such as {@code PT24H} are not supported.
 *
 * <p>The result must be greater than zero and no longer than {@link IdempotencyDefaults#MAX_TTL}.
 */
public final class TtlParser {

    private static final Pattern TTL_PATTERN = Pattern.compile("(\\d+)([smhd])", Pattern.CASE_INSENSITIVE);

    private TtlParser() {
    }

    /**
     * @param text a whole number followed by one unit: s, m, h or d - case-insensitive
     * @return the parsed duration (always positive)
     * @throws IllegalArgumentException if the text is not in that format, or if duration is greater than 365 days, or duration is not greater than 0 seconds
     */
    public static Duration parse(String text) {
        if (text == null) throw new IllegalArgumentException("Duration cannot be null");

        Matcher match = TTL_PATTERN.matcher(text.trim());
        if (!match.matches()) throw new IllegalArgumentException("Invalid duration format - does not match format: " + text);

        long amount;
        try {
            amount = Long.parseLong(match.group(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid duration format - number does not parse to long: " + text);
        }

        Duration duration;
        try {
            duration = Duration.of(amount, toUnit(match.group(2)));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Invalid duration - long overflow: " + text);
        }

        if (duration.compareTo(IdempotencyDefaults.MAX_TTL) > 0) throw new IllegalArgumentException("Invalid duration - duration must not be greater than 365 days: " + text);
        if (duration.isZero()) throw new IllegalArgumentException("Invalid duration - duration must be greater than 0 seconds: " + text);

        return duration;
    }

    private static ChronoUnit toUnit(String letter) {
        return switch (letter.toLowerCase(Locale.ROOT)) {
            case "s" -> ChronoUnit.SECONDS;
            case "m" -> ChronoUnit.MINUTES;
            case "h" -> ChronoUnit.HOURS;
            case "d" -> ChronoUnit.DAYS;
            default -> throw new IllegalStateException("Unreachable: the pattern only allows s, m, h or d: " + letter);
        };
    }
}
