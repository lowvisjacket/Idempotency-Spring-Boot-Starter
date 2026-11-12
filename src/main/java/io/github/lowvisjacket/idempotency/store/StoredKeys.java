package io.github.lowvisjacket.idempotency.store;

import io.github.lowvisjacket.idempotency.config.IdempotencyDefaults;
import io.github.lowvisjacket.idempotency.config.IdempotencyKeyValidator;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Builds the exact key written to the store from the client's idempotency key and who is calling.
 *
 * <p>The caller is part of the key so that two different callers who happen to send the same idempotency key
 * (and an identical request) can never be served each other's saved response.
 *
 * <p>The shapes, where {@code H} is a 64-character lowercase hex digest of the caller's name as UTF-8 and
 * {@code N} is the optional namespace:
 * <ul>
 *   <li>identified caller: {@code idempotency:v1:[N:]user:H:<key>}</li>
 *   <li>no caller (null name): {@code idempotency:v1:[N:]anonymous:<key>}</li>
 * </ul>
 * {@code H} is the plain SHA-256 of the name, or, when a secret is configured, HMAC-SHA256 keyed with it. Without a
 * secret, anyone who can read the store can recover usernames and emails by hashing a dictionary; with one they
 * cannot. The name is <b>not</b> Unicode-normalised: two names that differ only in normalisation form are two
 * different identities and must not share saved responses. A blank (but non-null) name is an identified caller, not
 * an anonymous one. The fixed length of {@code H} means the caller part can never run into the key part.
 *
 * <p>The namespace separates applications that share one Redis: two services using the same user names and keys
 * never read each other's records if their namespaces differ.
 */
public final class StoredKeys {

    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private StoredKeys() {
    }

    /** No namespace, unsalted hash: {@link #build(String, String, String, String)} with both left out. */
    public static String build(String callerName, String idempotencyKey) {
        return build(null, null, callerName, idempotencyKey);
    }

    /**
     * @param namespace      null or empty for none, else letters, digits, {@code . _ -}, at most 64
     * @param secret         null or empty for a plain SHA-256 caller hash, else the HMAC key
     * @param callerName     null for an anonymous caller
     * @param idempotencyKey the client's key, validated here
     * @throws IllegalArgumentException if the namespace is malformed
     */
    public static String build(String namespace, String secret, String callerName, String idempotencyKey) {
        String key = IdempotencyKeyValidator.validate(idempotencyKey);
        StringBuilder builder = new StringBuilder(IdempotencyDefaults.KEY_PREFIX);
        if (namespace != null && !namespace.isEmpty()) {
            if (!NAMESPACE.matcher(namespace).matches()) {
                throw new IllegalArgumentException("idempotency.namespace may only contain letters, digits, '.', '_' and '-' (at most 64)");
            }
            builder.append(namespace).append(':');
        }
        if (callerName == null) {
            builder.append(IdempotencyDefaults.ANONYMOUS_SEGMENT);
        } else {
            builder.append(IdempotencyDefaults.USER_SEGMENT)
                    .append(':')
                    .append(hashCaller(callerName, secret));
        }
        return builder.append(':').append(key).toString();
    }

    private static String hashCaller(String callerName, String secret) {
        byte[] name = callerName.getBytes(StandardCharsets.UTF_8);
        try {
            if (secret != null && !secret.isEmpty()) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                return HexFormat.of().formatHex(mac.doFinal(name));
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(name));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required on every JVM", e);
        }
    }
}
