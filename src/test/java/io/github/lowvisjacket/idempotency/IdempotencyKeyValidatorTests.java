package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.exception.InvalidIdempotencyKeyException;
import io.github.lowvisjacket.idempotency.config.IdempotencyKeyValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rules for IdempotencyKeyValidator.validate (change a rule here on purpose if you disagree with it):
 *  - The key must be present and not blank.
 *  - At most IdempotencyDefaults.MAX_KEY_LENGTH (255) characters.
 *  - Only printable ASCII with no spaces (characters ! through ~). That keeps Redis keys and log lines safe:
 *    no whitespace, control characters (newline, NUL, tab) or non-ASCII text.
 *  - No trimming or "fixing": a bad key is rejected, a good key is returned exactly as given.
 *  - A bad key throws InvalidIdempotencyKeyException (the interceptor turns that into 400).
 */
class IdempotencyKeyValidatorTests {

    private static String repeat(char c, int times) {
        return String.valueOf(c).repeat(times);
    }

    // --- Valid keys ---

    @ParameterizedTest(name = "\"{0}\" is a valid key")
    @ValueSource(strings = {
            "a",
            "key-1",
            "3f2b8c1e-6d4a-4b7e-9f10-2a5c7d9e1b34",   // UUID
            "order_12345",
            "user:42:payment:7",
            "a.b.c",
            "abc~def",
            "Zm9vYmFy==",                              // base64 padding
            "KEY=value",
            "UPPER-and-lower",
            "!#$%&'()*+,/;<>?@[]^`{|}"                 // every other printable symbol must be allowed
    })
    void acceptableKeysAreReturnedUnchanged(String key) {
        assertEquals(key, IdempotencyKeyValidator.validate(key));
    }

    @Test
    void aKeyExactlyAtTheMaximumLengthIsAccepted() {
        String key = repeat('k', IdempotencyDefaults.MAX_KEY_LENGTH);

        assertEquals(key, IdempotencyKeyValidator.validate(key));
    }

    // --- Missing or blank ---

    @ParameterizedTest(name = "[{index}] null / empty is rejected")
    @NullAndEmptySource
    void nullAndEmptyAreRejected(String key) {
        assertThrows(InvalidIdempotencyKeyException.class, () -> IdempotencyKeyValidator.validate(key));
    }

    @ParameterizedTest(name = "blank key #{index} is rejected")
    @ValueSource(strings = {" ", "   ", "\t", "\n", " \t\n "})
    void blankKeysAreRejected(String key) {
        assertThrows(InvalidIdempotencyKeyException.class, () -> IdempotencyKeyValidator.validate(key));
    }

    // --- Too long ---

    @Test
    void aKeyOneCharacterOverTheMaximumIsRejected() {
        String key = repeat('k', IdempotencyDefaults.MAX_KEY_LENGTH + 1);

        assertThrows(InvalidIdempotencyKeyException.class, () -> IdempotencyKeyValidator.validate(key));
    }

    @Test
    void anEnormousKeyIsRejectedQuickly() {
        String key = repeat('k', 5_000_000);

        assertTimeout(java.time.Duration.ofSeconds(2), () ->
                assertThrows(InvalidIdempotencyKeyException.class, () -> IdempotencyKeyValidator.validate(key)));
    }

    // --- Forbidden characters ---

    @ParameterizedTest(name = "key #{index} with a forbidden character is rejected")
    @ValueSource(strings = {
            "has space",
            " leadingSpace",
            "trailingSpace ",
            "tab\there",
            "new\nline",
            "carriage\rreturn",
            "nul\0byte",
            "bell\u0007",
            "escape\u001b[31m",         // terminal escape: log injection
            "delete\u007f",
            "del\u0080c1control",       // C1 control character
            "café",                // non-ASCII letter
            "ключ", // Cyrillic
            "鍵",                   // CJK
            "key-🔑",         // emoji (surrogate pair)
            "zero​width",          // zero-width space
            "line separator",
            "nbsp here"            // non-breaking space is NOT a normal space but must still be rejected
    })
    void keysWithForbiddenCharactersAreRejected(String key) {
        assertThrows(InvalidIdempotencyKeyException.class, () -> IdempotencyKeyValidator.validate(key));
    }

    @Test
    void aBadCharacterAnywhereInALongKeyIsStillFound() {
        String key = repeat('a', 200) + "\n" + repeat('b', 50);

        assertThrows(InvalidIdempotencyKeyException.class, () -> IdempotencyKeyValidator.validate(key));
    }

    // --- Injection-style values (they are just text to the validator, but must not slip through) ---

    @ParameterizedTest(name = "injection-style key #{index} is rejected")
    @ValueSource(strings = {
            "key\r\nSet-Cookie: x=1",   // HTTP header injection
            "key\nFLUSHALL",            // Redis protocol style
            "key with spaces and SET foo bar"
    })
    void injectionStyleKeysAreRejected(String key) {
        assertThrows(InvalidIdempotencyKeyException.class, () -> IdempotencyKeyValidator.validate(key));
    }

    // --- The exception itself ---

    @Test
    void theExceptionHasAHelpfulMessage() {
        InvalidIdempotencyKeyException thrown =
                assertThrows(InvalidIdempotencyKeyException.class, () -> IdempotencyKeyValidator.validate(""));

        assertNotNull(thrown.getMessage());
        assertFalse(thrown.getMessage().isBlank());
    }

    @Test
    void theMessageDoesNotEchoAnUnsafeKeyBack() {
        // The message ends up in a response and in logs; a newline or escape sequence in it would be log injection.
        InvalidIdempotencyKeyException thrown = assertThrows(InvalidIdempotencyKeyException.class,
                () -> IdempotencyKeyValidator.validate("evil\nforged log line"));

        assertFalse(thrown.getMessage().contains("\n"), "message must not contain the raw newline");
        assertFalse(thrown.getMessage().contains("forged log line"));
    }

    @Test
    void theExceptionIsUnchecked() {
        assertInstanceOf(RuntimeException.class, new InvalidIdempotencyKeyException("x"));
    }

    @Test
    void validatingDoesNotChangeTheInputOrHaveSideEffects() {
        String key = "stable-key";

        assertEquals(IdempotencyKeyValidator.validate(key), IdempotencyKeyValidator.validate(key));
    }

    // --- Added in review: error messages must never contain the rejected key ---

    @ParameterizedTest(name = "message for rejected key #{index} does not echo the key")
    @ValueSource(strings = {
            "escape\u001b[31mred",   // terminal escape sequence
            "nul\0byte",
            "caf\u00e9",             // non-ASCII
            "key-\uD83D\uDD11",      // emoji
            "has space"
    })
    void rejectionMessagesNeverContainTheKey(String key) {
        InvalidIdempotencyKeyException thrown =
                assertThrows(InvalidIdempotencyKeyException.class, () -> IdempotencyKeyValidator.validate(key));

        assertFalse(thrown.getMessage().contains(key), "message echoed the key: " + thrown.getMessage());
        assertFalse(thrown.getMessage().chars().anyMatch(c -> c < 0x20 || c > 0x7e),
                "message contains control or non-ASCII characters");
    }

    @Test
    void theTooLongMessageDoesNotEchoTheKeyBack() {
        String key = repeat('k', 5_000_000);

        InvalidIdempotencyKeyException thrown =
                assertThrows(InvalidIdempotencyKeyException.class, () -> IdempotencyKeyValidator.validate(key));

        assertTrue(thrown.getMessage().length() < 500, "message is " + thrown.getMessage().length() + " characters long");
    }

    @Test
    void theTooLongMessageStatesTheLimit() {
        InvalidIdempotencyKeyException thrown = assertThrows(InvalidIdempotencyKeyException.class,
                () -> IdempotencyKeyValidator.validate(repeat('k', IdempotencyDefaults.MAX_KEY_LENGTH + 1)));

        assertTrue(thrown.getMessage().contains(String.valueOf(IdempotencyDefaults.MAX_KEY_LENGTH)), thrown.getMessage());
    }
}
