package io.github.lowvisjacket.idempotency.store;

import io.github.lowvisjacket.idempotency.exception.RedisException;
import io.github.lowvisjacket.idempotency.model.IdempotencyRecord;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;


@Service
public class RedisLookup {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisLookup(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean keyExists(String key) {
        return redisTemplate.hasKey(key);
    }

    public boolean addKeyIfAbsent(IdempotencyRecord idempotencyRecord) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(idempotencyRecord.getKey(), toJSON(idempotencyRecord), idempotencyRecord.getTtl()));
    }

    public String getIdempotencyRecordJSON(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    public IdempotencyRecord getIdempotencyRecord(String JSON) {
        return objectMapper.readValue(JSON, IdempotencyRecord.class);
    }

    public String toJSON(IdempotencyRecord idempotencyRecord) {
        return objectMapper.writeValueAsString(idempotencyRecord);
    }

    public Long getTTLFromKey(String key) throws RedisException {
        if (!keyExists(key)) throw new RedisException("Key does not exist");
        return redisTemplate.getExpire(key);
    }

    /**
     * Overwrites an existing record and resets its TTL to the record's TTL.
     * Returns false (and creates nothing) if the key no longer exists.
     */
    public boolean completeRecord(IdempotencyRecord idempotencyRecord) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfPresent(idempotencyRecord.getKey(), toJSON(idempotencyRecord), idempotencyRecord.getTtl()));
    }

}
