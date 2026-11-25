package com.mediamesh.ingestion.storage.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import com.mediamesh.ingestion.config.IdempotencyProperties;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new IdempotencyService(
                redisTemplate,
                new ObjectMapper(),
                new IdempotencyProperties(Duration.ofHours(24), Duration.ofSeconds(60), 255));
    }

    @Test
    void bypassesRedisWhenKeyIsNullOrBlank() {
        AtomicInteger calls = new AtomicInteger();
        String result = service.execute("upload-init", UUID.randomUUID(), "   ", "req", String.class, () -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(1);
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void returns409ConflictWhenInFlightLockExistsWithSameFingerprint() {
        UUID userId = UUID.randomUUID();

        // Capture the IN_PROGRESS record written by the first call
        String[] stored = new String[1];
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> {
                    if (stored[0] == null) {
                        stored[0] = inv.getArgument(1);
                        return true;
                    }
                    return false;
                });
        when(valueOperations.get(anyString())).thenAnswer(inv -> stored[0]);

        // Simulate first request in-flight by invoking nested second request inside supplier
        assertThatThrownBy(() -> service.execute("upload-init", userId, "k1", "payload", String.class, () -> {
            return service.execute("upload-init", userId, "k1", "payload", String.class, () -> "should-not-run");
        }))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void releasesInFlightLockWhenActionThrows() {
        UUID userId = UUID.randomUUID();
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertThatThrownBy(() -> service.execute("upload-init", userId, "k-fail", "payload", String.class, () -> {
            throw new IllegalStateException("boom");
        }))
                .isInstanceOf(IllegalStateException.class);

        verify(redisTemplate).delete("idempotency:upload-init:" + userId + ":k-fail");
    }

    @Test
    void failsClosedWith503WhenRedisIsUnavailable() {
        UUID userId = UUID.randomUUID();
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new DataAccessResourceFailureException("Redis down"));

        assertThatThrownBy(() -> service.execute("upload-init", userId, "k-down", "payload", String.class, () -> "ok"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.STORAGE_UNAVAILABLE));
    }
}
