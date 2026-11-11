package io.github.lowvisjacket.idempotency;

import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.exception.InvalidIdempotencyKeyException;
import io.github.lowvisjacket.idempotency.store.StoredKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rules for StoredKeys.build (change a rule here on purpose if you disagree with it):
 *  - Identified caller:  "idempotency:v1:user:" + H + ":" + key
 *      H = lowercase SHA-256 hex (64 chars) of the caller name, after Unicode NFC normalization, as UTF-8 bytes.
 *  - No caller (null, empty, or blank name): "idempotency:v1:anonymous:" + key
 *  - The caller name is hashed, never written as is. Any text is a legal caller name, even weird text.
 *  - Caller names and keys are case-sensitive.
 *  - The idempotency key is validated first (IdempotencyKeyValidator rules), for anonymous callers too,
 *    and is used unchanged.
 *  - The result is always printable ASCII with no spaces.
 */
class StoredKeysTests {

    private static final String USER_PREFIX = IdempotencyDefaults.KEY_PREFIX + IdempotencyDefaults.USER_SEGMENT + ":";
    private static final String ANON_PREFIX = IdempotencyDefaults.KEY_PREFIX + IdempotencyDefaults.ANONYMOUS_SEGMENT + ":";

    /** An independent way to compute H, so the tests do not trust the code they are checking. */
    private static String sha256Hex(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime must support SHA-256, so this cannot happen; unchecked keeps the tests tidy.
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
    }

    private static String repeat(char c, int times) {
        return String.valueOf(c).repeat(times);
    }

    // --- The constants ---

    @Test
    void thePrefixConstantsAreWhatTheseTestsAssume() throws Exception {
        assertEquals("idempotency:v1:", IdempotencyDefaults.KEY_PREFIX);
        assertEquals("user", IdempotencyDefaults.USER_SEGMENT);
        assertEquals("anonymous", IdempotencyDefaults.ANONYMOUS_SEGMENT);
    }

    // --- Shape: identified caller ---

    @Test
    void anIdentifiedCallerGetsTheUserShape() throws Exception {
        assertEquals(USER_PREFIX + sha256Hex("alice") + ":order-1", StoredKeys.build("alice", "order-1"));
    }

    @ParameterizedTest(name = "sha256(\"{0}\") is {1}")
    @CsvSource({
            "alice,              2bd806c97f0e00af1a1fc3328fa763a9269723c8db8fac4f93af71db186d6e90",
            "bob,                81b637d8fcd2c6da6359e6963113a1170de795e4b725b84d1e0b4cfd9ec58ce9",
            "alice@example.com,  ff8d9819fc0e12bf0d24892e45987e249a28dce836a85cad60e28eaaa8c6d976",
            "anonymous,          2f183a4e64493af3f377f745eda502363cd3e7ef6e4d266d444758de0a85fcc8"
    })
    void theCallerPartIsTheKnownSha256OfTheName(String name, String expectedHash) throws Exception {
        // Known answers computed outside Java, so a wrong hash algorithm or encoding cannot hide.
        assertEquals(USER_PREFIX + expectedHash + ":k", StoredKeys.build(name, "k"));
    }

    @Test
    void theCallerHashIsLowercaseHexOfExactlySixtyFourCharacters() throws NoSuchAlgorithmException {
        String result = StoredKeys.build("alice", "k");

        assertTrue(result.matches("idempotency:v1:user:[0-9a-f]{64}:k"), result);
    }

    // --- Shape: anonymous caller ---

    @Test
    void aNullCallerIsAnonymous() throws Exception {
        assertEquals(ANON_PREFIX + "order-1", StoredKeys.build(null, "order-1"));
    }

    @ParameterizedTest(name = "blank caller #{index} is anonymous")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n", "\r\n", " \t \n "})
    void anEmptyOrBlankCallerIsAnonymous(String blank) throws Exception {
        assertEquals(ANON_PREFIX + "k", StoredKeys.build(blank, "k"));
    }

    @Test
    void aCallerNamedAnonymousIsNotTheAnonymousCaller() throws Exception {
        String named = StoredKeys.build("anonymous", "k");
        String anonymous = StoredKeys.build(null, "k");

        assertNotEquals(anonymous, named);
        assertTrue(named.startsWith(USER_PREFIX));
        assertTrue(anonymous.startsWith(ANON_PREFIX));
    }

    @Test
    void aCallerWhoseNameHasSurroundingSpacesIsStillAnIdentifiedCaller() throws Exception {
        // Only an entirely blank name is anonymous. Spaces around a real name are part of the name; do not trim.
        String result = StoredKeys.build(" alice ", "k");

        assertEquals(USER_PREFIX + sha256Hex(" alice ") + ":k", result);
        assertNotEquals(StoredKeys.build("alice", "k"), result);
    }

    // --- The point of the whole class: separation ---

    @Test
    void sameKeyDifferentCallersGetDifferentStoreKeys() throws Exception {
        assertNotEquals(StoredKeys.build("alice", "pay-1"), StoredKeys.build("bob", "pay-1"));
    }

    @Test
    void sameCallerDifferentKeysGetDifferentStoreKeys() throws Exception {
        assertNotEquals(StoredKeys.build("alice", "pay-1"), StoredKeys.build("alice", "pay-2"));
    }

    @Test
    void anIdentifiedCallerAndAnAnonymousCallerNeverShareAKey() throws Exception {
        assertNotEquals(StoredKeys.build("alice", "pay-1"), StoredKeys.build(null, "pay-1"));
    }

    @Test
    void callerNamesAreCaseSensitive() throws Exception {
        assertNotEquals(StoredKeys.build("Alice", "k"), StoredKeys.build("alice", "k"));
    }

    @Test
    void idempotencyKeysAreCaseSensitive() throws Exception {
        assertNotEquals(StoredKeys.build("alice", "Key"), StoredKeys.build("alice", "key"));
    }

    @Test
    void theSameInputsAlwaysGiveTheSameKey() throws Exception {
        assertEquals(StoredKeys.build("alice", "k"), StoredKeys.build("alice", "k"));
    }

    // --- Boundary tricks: moving characters between the caller and the key must never collide ---

    @Test
    void movingAColonBetweenCallerAndKeyDoesNotCollide() throws Exception {
        assertNotEquals(StoredKeys.build("a:b", "c"), StoredKeys.build("a", "b:c"));
    }

    @Test
    void aKeyThatLooksLikeTheUserPrefixCannotImpersonateAnotherCaller() throws Exception {
        // An anonymous caller sends a key shaped like "user:<alice's hash>:k". It must not equal alice's real key.
        String forged = StoredKeys.build(null, "user:" + sha256Hex("alice") + ":k");
        String real = StoredKeys.build("alice", "k");

        assertNotEquals(real, forged);
    }

    @Test
    void aCallerNameThatIsAnotherCallersHashDoesNotCollide() throws Exception {
        // Naming yourself after alice's hash must not land in alice's space (the name is hashed again).
        assertNotEquals(StoredKeys.build("alice", "k"), StoredKeys.build(sha256Hex("alice"), "k"));
    }

    @ParameterizedTest(name = "caller \"{0}\" cannot reach another caller's space")
    @ValueSource(strings = {"alice:", ":alice", "alice::", "user:alice", "idempotency:v1:user:alice", "alice\n", "*", "alice*"})
    void callersWhoseNamesLookStructuredStayInTheirOwnSpace(String name) throws Exception {
        Set<String> seen = new HashSet<>();
        for (String other : List.of("alice", "bob", name)) {
            seen.add(StoredKeys.build(other, "k"));
        }

        assertEquals(name.equals("alice") ? 2 : 3, seen.size());
    }

    // --- Unicode and odd caller names ---

    @Test
    void composedAndDecomposedFormsOfTheSameNameGiveTheSameKey() throws Exception {
        String composed = "José";      // é as one character
        String decomposed = "José";   // e followed by a combining accent

        assertEquals(StoredKeys.build(composed, "k"), StoredKeys.build(decomposed, "k"));
    }

    @Test
    void differentNamesThatLookAlikeStayDifferent() throws Exception {
        // Cyrillic "а" (U+0430) is not Latin "a": different people, different keys.
        assertNotEquals(StoredKeys.build("alice", "k"), StoredKeys.build("аlice", "k"));
    }

    @ParameterizedTest(name = "unusual caller #{index} is accepted and gives clean output")
    @ValueSource(strings = {
            "ключ",       // Cyrillic
            "鍵世界",             // CJK
            "user 🔑",              // emoji
            "with space",
            "tab\there",
            "new\nline",
            "nul\0byte",
            "escape\u001b[31m",
            "quote\"and'apostrophe",
            "zero​width",
            "line separator",
            "alice@example.com",
            "CN=alice,OU=staff,O=Example"     // a certificate-style name
    })
    void anyCallerNameIsAcceptedAndTheOutputStaysCleanAscii(String name) throws Exception {
        String result = StoredKeys.build(name, "order-1");

        assertTrue(result.matches("[!-~]+"), "output must be printable ASCII with no spaces: " + result);
        assertTrue(result.matches("idempotency:v1:user:[0-9a-f]{64}:order-1"), result);
    }

    @Test
    void theRawCallerNameNeverAppearsInTheKey() throws Exception {
        String result = StoredKeys.build("alice@example.com", "order-1");

        assertFalse(result.contains("alice"));
        assertFalse(result.contains("example"));
        assertFalse(result.contains("@"));
    }

    @Test
    void anEnormousCallerNameStillGivesAFixedSizeKey() throws Exception {
        String result = StoredKeys.build(repeat('a', 2_000_000), "k");

        assertEquals(USER_PREFIX.length() + 64 + ":k".length(), result.length());
    }

    @Test
    void aStringOfOnlyLoneSurrogatesDoesNotCrash() throws Exception {
        assertDoesNotThrow(() -> StoredKeys.build("😀\uD83D", "k"));
    }

    // --- The idempotency key is validated, and used unchanged ---

    @ParameterizedTest(name = "key \"{0}\" is embedded unchanged")
    @ValueSource(strings = {"a", "order-1", "user:42:payment:7", "3f2b8c1e-6d4a-4b7e-9f10-2a5c7d9e1b34", "a.b~c=d", "!#$%&'()*+,/;<>?@[]^`{|}"})
    void aValidKeyIsUsedExactlyAsGiven(String key) throws Exception {
        assertTrue(StoredKeys.build("alice", key).endsWith(":" + key));
        assertEquals(ANON_PREFIX + key, StoredKeys.build(null, key));
    }

    @Test
    void aKeyAtTheMaximumLengthIsAccepted() throws Exception {
        String key = repeat('k', IdempotencyDefaults.MAX_KEY_LENGTH);

        assertTrue(StoredKeys.build("alice", key).endsWith(key));
        assertTrue(StoredKeys.build("alice", key).length() < 512, "store keys must stay a sensible size");
    }

    @ParameterizedTest(name = "invalid key #{index} is rejected for an identified caller")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "has space", "tab\there", "new\nline", "nul\0byte", "café", "key-🔑", "line sep"})
    void anInvalidKeyIsRejectedForAnIdentifiedCaller(String key) throws Exception {
        assertThrows(InvalidIdempotencyKeyException.class, () -> StoredKeys.build("alice", key));
    }

    @ParameterizedTest(name = "invalid key #{index} is rejected for an anonymous caller")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "has space", "new\nline", "café"})
    void anInvalidKeyIsRejectedForAnAnonymousCallerToo(String key) throws Exception {
        assertThrows(InvalidIdempotencyKeyException.class, () -> StoredKeys.build(null, key));
    }

    @Test
    void aTooLongKeyIsRejected() throws Exception {
        String key = repeat('k', IdempotencyDefaults.MAX_KEY_LENGTH + 1);

        assertThrows(InvalidIdempotencyKeyException.class, () -> StoredKeys.build("alice", key));
    }

    @Test
    void aBadKeyIsRejectedEvenWhenTheCallerNameIsAlsoUnusual() throws Exception {
        assertThrows(InvalidIdempotencyKeyException.class, () -> StoredKeys.build("bad\0caller", "bad key"));
    }

    @Test
    void theErrorForABadKeyNeverContainsTheKeyOrTheCaller() throws Exception {
        InvalidIdempotencyKeyException thrown = assertThrows(InvalidIdempotencyKeyException.class,
                () -> StoredKeys.build("alice@example.com", "evil\nforged log line"));

        assertFalse(thrown.getMessage().contains("forged"));
        assertFalse(thrown.getMessage().contains("alice"));
        assertFalse(thrown.getMessage().contains("\n"));
    }

    // --- Scale and sharing ---

    @Test
    void manyDistinctCallersAndKeysNeverProduceTheSameStoreKey() throws Exception {
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (int caller = 0; caller < 200; caller++) {
            for (int key = 0; key < 50; key++) {
                seen.add(StoredKeys.build("user-" + caller, "key-" + key));
                total++;
            }
        }
        assertEquals(total, seen.size());
    }

    @Test
    void concurrentUseGivesTheSameAnswersAsSingleThreadedUse() throws Exception {
        String expected = StoredKeys.build("alice", "k");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                results.add(pool.submit((Callable<Boolean>) () -> {
                    for (int i = 0; i < 1000; i++) {
                        if (!expected.equals(StoredKeys.build("alice", "k"))) return false;
                        if (expected.equals(StoredKeys.build("bob", "k"))) return false;
                    }
                    return true;
                }));
            }
            for (Future<Boolean> result : results) {
                assertTrue(result.get(), "a thread saw a wrong or mixed-up key");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
