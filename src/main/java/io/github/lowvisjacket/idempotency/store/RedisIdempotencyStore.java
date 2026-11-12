package io.github.lowvisjacket.idempotency.store;

import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

public class RedisIdempotencyStore implements IdempotencyStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisIdempotencyStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
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
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfPresent(record.getKey(), serialize(record), record.getTtl()));
    }

    @Override
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
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
