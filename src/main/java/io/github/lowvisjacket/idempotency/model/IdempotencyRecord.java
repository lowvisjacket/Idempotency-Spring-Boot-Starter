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
