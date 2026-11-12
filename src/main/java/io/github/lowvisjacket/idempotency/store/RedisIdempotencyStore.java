package io.github.lowvisjacket.idempotency.store;

import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class RedisIdempotencyStore implements IdempotencyStore {

    /** Replaces the value only if the stored record's {@code owner} equals ARGV[1]. ARGV[2] is the new JSON, ARGV[3] the TTL in ms. */
    private static final DefaultRedisScript<Long> COMPLETE_IF_OWNER = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if not v then return 0 end
            local ok, rec = pcall(cjson.decode, v)
            if not ok or type(rec) ~= 'table' or rec['owner'] ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);

    /** Deletes the key only if the stored record's {@code owner} equals ARGV[1]. */
    private static final DefaultRedisScript<Long> DELETE_IF_OWNER = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if not v then return 0 end
            local ok, rec = pcall(cjson.decode, v)
            if not ok or type(rec) ~= 'table' or rec['owner'] ~= ARGV[1] then return 0 end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Uses a private {@link ObjectMapper} that only this class touches. The application's mapper is deliberately not
     * used for records: an application may enable default typing, register modules, rename or hide properties, or
     * change how dates and unknown fields are handled, and any of that would either break the stored format or make
     * deserialising text read from a shared store dangerous.
     */
    public RedisIdempotencyStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.objectMapper = JsonMapper.builder()
                // a record written by a newer version may have more fields; ignore them rather than fail every request
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * @param objectMapper ignored; kept so existing code that passes the application's mapper still compiles
     * @deprecated the store uses its own mapper, see {@link #RedisIdempotencyStore(StringRedisTemplate)}
     */
    @Deprecated(forRemoval = true)
    public RedisIdempotencyStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate);
    }

    @Override
    public boolean claim(IdempotencyRecord record) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(record.getKey(), serialize(record), record.getTtl()));
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) return Optional.empty();
        IdempotencyRecord record = deserialize(json);
        record.setKey(key);
        return Optional.of(record);
    }

    @Override
    public boolean complete(IdempotencyRecord record) {
        if (record.getOwner() != null) {
            Long done = redisTemplate.execute(COMPLETE_IF_OWNER, List.of(record.getKey()), record.getOwner(),
                    serialize(record), Long.toString(record.getTtl().toMillis()));
            return Long.valueOf(1).equals(done);
        }
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfPresent(record.getKey(), serialize(record), record.getTtl()));
    }

    @Override
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    @Override
    public boolean delete(String key, String owner) {
        if (owner == null) return delete(key);
        return Long.valueOf(1).equals(redisTemplate.execute(DELETE_IF_OWNER, List.of(key), owner));
    }

    @Override
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    @Override
    public Optional<Duration> getRemainingTtl(String key) {
        Long seconds = redisTemplate.getExpire(key);
        if (seconds == null || seconds < 0) return Optional.empty();
        return Optional.of(Duration.ofSeconds(seconds));
    }

    private String serialize(IdempotencyRecord record) {
        return objectMapper.writeValueAsString(record);
    }

    private IdempotencyRecord deserialize(String json) {
        return objectMapper.readValue(json, IdempotencyRecord.class);
    }
}
