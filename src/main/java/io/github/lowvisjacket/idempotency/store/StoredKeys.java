package io.github.lowvisjacket.idempotency.store;

import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.config.IdempotencyKeyValidator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

/**
 * Builds the exact key written to the store from the client's idempotency key and who is calling.
 *
 * <p>The caller is part of the key so that two different callers who happen to send the same idempotency key
 * (and an identical request) can never be served each other's saved response.
 *
 * <p>The shapes, where {@code H} is the lowercase SHA-256 hex (64 characters) of the caller's name after Unicode
 * NFC normalization, encoded as UTF-8:
 * <ul>
 *   <li>identified caller: {@code idempotency:v1:user:H:<key>}</li>
 *   <li>no caller (null or blank name): {@code idempotency:v1:anonymous:<key>}</li>
 * </ul>
 * The caller's name is hashed, never written as is, so usernames and emails do not appear in the store, and the
 * fixed length of {@code H} means the caller part can never run into the key part. Caller names and keys are
 * case-sensitive. See StoredKeysTests for the exact rules.
 *
 * @see io.github.lowvisjacket.idempotency.config.IdempotencyDefaults
 */
public final class StoredKeys {


    private StoredKeys() {
    }

    /**
     * Builds the store key for one request.
     *
     * @param callerName     the authenticated caller's name (for example {@code Principal.getName()}) - may be null or
     *                       blank, which means an anonymous caller; any other text is accepted, including spaces,
     *                       colons, control characters and non-ASCII, because it is hashed
     * @param idempotencyKey the client's key; must satisfy the rules of {@code IdempotencyKeyValidator} (present,
     *                       at most 255 characters, printable ASCII with no spaces) and is used unchanged
     * @return the store key; always printable ASCII with no spaces
     * @throws io.github.lowvisjacket.idempotency.exception.InvalidIdempotencyKeyException if the idempotency key is
     *                                                                                     not acceptable (this applies to anonymous callers too)
     */
    public static String build(String callerName, String idempotencyKey) {
        String key = IdempotencyKeyValidator.validate(idempotencyKey);
        StringBuilder builder = new StringBuilder(IdempotencyDefaults.KEY_PREFIX);

        if (callerName == null || callerName.isBlank()) {
            builder.append(IdempotencyDefaults.ANONYMOUS_SEGMENT);
        } else {
            builder.append(IdempotencyDefaults.USER_SEGMENT)
                    .append(':')
                    .append(hashCaller(callerName));
        }

        return builder.append(':').append(key).toString();
    }

    /** Lowercase SHA-256 hex of the NFC-normalized name, encoded as UTF-8. */
    private static String hashCaller(String callerName) {
        String normalized = Normalizer.normalize(callerName, Normalizer.Form.NFC);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
    }
}
