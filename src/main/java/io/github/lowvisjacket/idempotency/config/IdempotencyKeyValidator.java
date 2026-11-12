package io.github.lowvisjacket.idempotency.config;

import io.github.lowvisjacket.idempotency.exception.InvalidIdempotencyKeyException;

import java.util.regex.Pattern;

/**
 * Checks the idempotency key sent by a client before it is used to build a Redis key.
 *
 * <p>The key comes from an untrusted request header, so it is held to a strict allow-list. A valid key:
 * <ul>
 *   <li>is present and not blank,</li>
 *   <li>has at most {@link IdempotencyDefaults#MAX_KEY_LENGTH} characters, and</li>
 *   <li>contains only printable ASCII characters ({@code !} through {@code ~}), so no spaces, control characters
 *       (newline, tab, NUL, escape) or non-ASCII text.</li>
 * </ul>
 *
 * <p>A valid key is returned exactly as given: it is never trimmed or altered. Error messages describe the rule
 * that failed and never contain the key itself, because the message is written to responses and logs.
 *
 * @see InvalidIdempotencyKeyException
 */
public final class IdempotencyKeyValidator {

    /** One or more printable ASCII characters: {@code !} (0x21) through {@code ~} (0x7E). Excludes the space. */
    private static final Pattern VALID_KEY = Pattern.compile("[!-~]+");

    private IdempotencyKeyValidator() {
    }

    /**
     * Validates a client-supplied idempotency key.
     *
     * @param key the raw header value - may be null; must not be blank, at most 255 characters ({@code IdempotencyDefaults.MAX_KEY_LENGTH}), and only printable ASCII with no spaces (characters {@code !} through {@code ~})
     * @return the same key, unchanged (never trimmed or altered), when it is acceptable
     * @throws InvalidIdempotencyKeyException if the key is null, empty or blank, is longer than 255 characters, or contains a space, a control character (newline, tab, NUL, escape) or any non-ASCII character
     */
    public static String validate(String key) {
        if (key == null) {
            throw new InvalidIdempotencyKeyException("Idempotency key is required");
        }
        if (key.isBlank()) {
            throw new InvalidIdempotencyKeyException("Idempotency key must not be blank");
        }
        if (key.length() > IdempotencyDefaults.MAX_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency key must be at most " + IdempotencyDefaults.MAX_KEY_LENGTH + " characters");
        }
        if (!VALID_KEY.matcher(key).matches()) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency key may only contain printable ASCII characters (! through ~) and no spaces");
        }
        return key;
    }
}
