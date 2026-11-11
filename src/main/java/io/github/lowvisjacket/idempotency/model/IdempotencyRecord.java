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
    private String header;
    private String body;
    private int httpStatus;
    @JsonIgnore
    private Duration ttl;
    public IdempotencyRecord(String key, String fingerprint, String header, String body, int httpStatus, Duration ttl) {
        this.key = key;
        this.fingerprint = fingerprint;
        this.header = header;
        this.body = body;
        this.httpStatus = httpStatus;
        this.ttl = ttl;
    }

}
