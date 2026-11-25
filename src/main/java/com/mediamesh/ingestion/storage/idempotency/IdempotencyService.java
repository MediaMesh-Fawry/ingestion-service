package com.mediamesh.ingestion.storage.idempotency;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import com.mediamesh.ingestion.config.IdempotencyProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis-backed deduplication engine for {@code Idempotency-Key} requests,
 * backing the {@link Idempotent} / {@link IdempotencyAspect} AOP layer.
 * Scopes keys by {@code (operation, userId, idempotencyKey)}, verifies request
 * payload fingerprints, guards concurrent duplicates with an in-flight lock,
 * and fails closed with 503 if Redis is unavailable when an idempotency key is supplied.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:";
    private static final String STATE_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATE_COMPLETED = "COMPLETED";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final IdempotencyProperties properties;

    /**
     * Executes {@code action} with idempotency deduplication when
     * {@code idempotencyKey} is non-blank.
     */
    public <T> T execute(
            String operation,
            UUID userId,
            String idempotencyKey,
            Object requestPayload,
            Class<T> responseType,
            Supplier<T> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return action.get();
        }

        String normalizedKey = idempotencyKey.trim();
        if (normalizedKey.length() > properties.maxKeyLength()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    "Idempotency-Key exceeds maximum length of " + properties.maxKeyLength());
        }

        String redisKey = KEY_PREFIX + operation + ":" + userId + ":" + normalizedKey;
        String fingerprint = computeFingerprint(operation, requestPayload);
        String inProgressRecord = serializeRecord(new IdempotencyRecord(STATE_IN_PROGRESS, fingerprint, null));

        if (!acquireLock(redisKey, inProgressRecord)) {
            return handleExistingRecord(redisKey, fingerprint, responseType);
        }

        T result;
        try {
            result = action.get();
        } catch (RuntimeException | Error ex) {
            safeDelete(redisKey);
            throw ex;
        }

        try {
            String responseJson = objectMapper.writeValueAsString(result);
            String completedRecord = serializeRecord(
                    new IdempotencyRecord(STATE_COMPLETED, fingerprint, responseJson));
            redisTemplate.opsForValue().set(redisKey, completedRecord, properties.ttl());
        } catch (DataAccessException ex) {
            safeDelete(redisKey);
            throw ApiException.storageUnavailable("Idempotency store is unavailable", ex);
        } catch (JacksonException ex) {
            safeDelete(redisKey);
            throw new IllegalStateException("Failed to serialize idempotency response", ex);
        }

        return result;
    }

    private boolean acquireLock(String redisKey, String inProgressRecord) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(redisKey, inProgressRecord, properties.lockTtl()));
        } catch (DataAccessException ex) {
            throw ApiException.storageUnavailable("Idempotency store is unavailable", ex);
        }
    }

    private <T> T handleExistingRecord(String redisKey, String fingerprint, Class<T> responseType) {
        String existingRaw;
        try {
            existingRaw = redisTemplate.opsForValue().get(redisKey);
        } catch (DataAccessException ex) {
            throw ApiException.storageUnavailable("Idempotency store is unavailable", ex);
        }

        if (existingRaw == null) {
            // Key expired between setIfAbsent and get; ask client to retry cleanly.
            throw ApiException.conflict("Request with this Idempotency-Key is currently transitioning; please retry");
        }

        IdempotencyRecord record = deserializeRecord(existingRaw);
        if (!fingerprint.equals(record.fingerprint())) {
            throw ApiException.idempotencyKeyReused(
                    "Idempotency-Key was already used with a different request payload");
        }

        if (STATE_IN_PROGRESS.equals(record.state())) {
            throw ApiException.conflict("A request with this Idempotency-Key is already in progress");
        }

        if (STATE_COMPLETED.equals(record.state()) && record.responseJson() != null) {
            try {
                log.debug("Replaying cached idempotent response for key={}", redisKey);
                return objectMapper.readValue(record.responseJson(), responseType);
            } catch (JacksonException ex) {
                throw new IllegalStateException("Failed to deserialize cached idempotency response", ex);
            }
        }

        throw ApiException.conflict("Invalid idempotency state for key");
    }

    private void safeDelete(String redisKey) {
        try {
            redisTemplate.delete(redisKey);
        } catch (DataAccessException ex) {
            log.warn("Failed to release in-flight idempotency lock key={}", redisKey, ex);
        }
    }

    private String computeFingerprint(String operation, Object requestPayload) {
        try {
            String payloadJson = requestPayload == null ? "" : objectMapper.writeValueAsString(requestPayload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(operation.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            byte[] hash = digest.digest(payloadJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JacksonException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Failed to compute request fingerprint", ex);
        }
    }

    private String serializeRecord(IdempotencyRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize idempotency record", ex);
        }
    }

    private IdempotencyRecord deserializeRecord(String raw) {
        try {
            return objectMapper.readValue(raw, IdempotencyRecord.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to deserialize idempotency record", ex);
        }
    }

    private record IdempotencyRecord(String state, String fingerprint, String responseJson) {
    }
}
