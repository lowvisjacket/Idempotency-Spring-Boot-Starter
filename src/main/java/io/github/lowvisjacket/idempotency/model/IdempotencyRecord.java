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
    private String body;
    private int httpStatus;
    @JsonIgnore
    private Duration ttl;
    public IdempotencyRecord(String key, String fingerprint, String contentType, String body, int httpStatus, Duration ttl) {
        this.key = key;
        this.fingerprint = fingerprint;
        this.contentType = contentType;
        this.body = body;
        this.httpStatus = httpStatus;
        this.ttl = ttl;
    }

}
