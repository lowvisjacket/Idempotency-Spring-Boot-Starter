package io.github.lowvisjacket.idempotency.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

@Getter
@NoArgsConstructor
@Setter
public class IdempotencyRecord {
    @JsonIgnore
    private String key;
    private String fingerprint;

    /**
     * A random token that identifies the request that claimed the key. Only that request may complete or delete the
     * record. Without it, a request whose record expired mid-flight would overwrite (or delete) the record of the
     * next request that claimed the same key, and a third request could then run as well.
     * May be null for a record written by a version that had no token.
     */
    private String owner;

    /**
     * False when the request ran to completion but its response could not be kept (too large, not UTF-8 text). The
     * record then only says "this key was used and finished with {@code httpStatus}", and a retry is refused rather
     * than run a second time. Records written before this field existed read as replayable.
     */
    private boolean replayable = true;
    private Status status = Status.PENDING;
    /** The response's {@code Content-Type} exactly as sent (including any charset), or null if it had none. */
    private String contentType;
    /**
     * Response headers worth replaying (see {@code IdempotencyDefaults.REPLAYED_RESPONSE_HEADERS}), each name with its
     * values in order. Never null: a record saved before this field existed has none.
     */
    private Map<String, List<String>> headers = new LinkedHashMap<>();
    private String body;
    private int httpStatus;
    @JsonIgnore
    private Duration ttl;
    public IdempotencyRecord(String key, String fingerprint, String contentType, String body, int httpStatus, Duration ttl) {
        this(key, fingerprint, contentType, null, body, httpStatus, ttl);
    }

    /** @param headers the response headers to replay; null means none (the map is copied) */
    public IdempotencyRecord(String key, String fingerprint, String contentType, Map<String, List<String>> headers,
                             String body, int httpStatus, Duration ttl) {
        setHeaders(headers);
        this.key = key;
        this.fingerprint = fingerprint;
        this.contentType = contentType;
        this.body = body;
        this.httpStatus = httpStatus;
        this.ttl = ttl;
    }

    /** @param headers the response headers to replay; null becomes empty, and the map is copied */
    public void setHeaders(Map<String, List<String>> headers) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((name, values) -> copy.put(name, values == null ? new ArrayList<>() : new ArrayList<>(values)));
        }
        this.headers = copy;
    }
}
