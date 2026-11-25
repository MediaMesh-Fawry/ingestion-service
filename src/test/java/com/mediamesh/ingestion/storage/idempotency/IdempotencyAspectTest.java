package com.mediamesh.ingestion.storage.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import com.mediamesh.ingestion.config.IdempotencyProperties;
import com.mediamesh.ingestion.security.IdentityHeaderResolver;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class IdempotencyAspectTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TestController proxy;
    private AtomicInteger invocations;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ObjectMapper objectMapper = new ObjectMapper();
        IdempotencyService idempotencyService = new IdempotencyService(
                redisTemplate,
                objectMapper,
                new IdempotencyProperties(Duration.ofHours(24), Duration.ofSeconds(60), 255));
        IdempotencyAspect aspect = new IdempotencyAspect(idempotencyService, objectMapper);

        invocations = new AtomicInteger();
        TestController target = new TestController(invocations);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        proxy = factory.getProxy();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void bypassesRedisWhenHeaderMissingAndNotRequired() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        ResponseEntity<SampleResponse> res = proxy.optionalEndpoint(
                UUID.randomUUID().toString(), new SampleRequest("file.mp4", 10L));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(invocations).hasValue(1);
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void rejectsMissingHeaderWith400WhenRequired() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(IdentityHeaderResolver.USER_ID_HEADER, UUID.randomUUID().toString());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        assertThatThrownBy(() -> proxy.requiredEndpoint(new SampleRequest("file.mp4", 10L)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));

        assertThat(invocations).hasValue(0);
    }

    @Test
    void failsClosedWith401WhenUserIdHeaderInvalidBeforeTouchingRedis() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(IdentityHeaderResolver.USER_ID_HEADER, "not-a-uuid");
        req.addHeader(IdentityHeaderResolver.IDEMPOTENCY_KEY_HEADER, "key-1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        assertThatThrownBy(() -> proxy.optionalEndpoint("not-a-uuid", new SampleRequest("file.mp4", 10L)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));

        assertThat(invocations).hasValue(0);
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void cachesAndReplaysResponseEntityStatusHeadersAndTypedBody() {
        String userId = UUID.randomUUID().toString();
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
        lenient().doAnswer(inv -> {
            stored[0] = inv.getArgument(1);
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(IdentityHeaderResolver.USER_ID_HEADER, userId);
        req.addHeader(IdentityHeaderResolver.IDEMPOTENCY_KEY_HEADER, "key-replay");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        SampleRequest payload = new SampleRequest("movie.mp4", 1024L);
        ResponseEntity<SampleResponse> first = proxy.optionalEndpoint(userId, payload);
        ResponseEntity<SampleResponse> replay = proxy.optionalEndpoint(userId, payload);

        assertThat(invocations).hasValue(1);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(replay.getHeaders().getCacheControl()).isEqualTo(first.getHeaders().getCacheControl());
        assertThat(replay.getBody()).isEqualTo(first.getBody());
    }

    @Test
    void fingerprintsPathVariableAndOptionalBody() {
        String userId = UUID.randomUUID().toString();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
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
        lenient().doAnswer(inv -> {
            stored[0] = inv.getArgument(1);
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(IdentityHeaderResolver.USER_ID_HEADER, userId);
        req.addHeader(IdentityHeaderResolver.IDEMPOTENCY_KEY_HEADER, "key-path");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        proxy.pathAndOptionalBodyEndpoint(id1, null);

        // Same key with different @PathVariable must reject with 422 IDEMPOTENCY_KEY_REUSED
        assertThatThrownBy(() -> proxy.pathAndOptionalBodyEndpoint(id2, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void releasesRedisLockWhenControllerThrows() {
        String userId = UUID.randomUUID().toString();
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(IdentityHeaderResolver.USER_ID_HEADER, userId);
        req.addHeader(IdentityHeaderResolver.IDEMPOTENCY_KEY_HEADER, "key-err");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        assertThatThrownBy(() -> proxy.failingEndpoint())
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CONFLICT));

        verify(redisTemplate).delete("idempotency:test-fail:" + userId + ":key-err");
    }

    static class TestController {
        private final AtomicInteger invocations;

        TestController(AtomicInteger invocations) {
            this.invocations = invocations;
        }

        @Idempotent(operation = "test-optional")
        public ResponseEntity<SampleResponse> optionalEndpoint(
                @RequestHeader(IdentityHeaderResolver.USER_ID_HEADER) String userId,
                @RequestBody SampleRequest request) {
            int count = invocations.incrementAndGet();
            return ResponseEntity.created(URI.create("/api/v1/uploads/" + count))
                    .cacheControl(CacheControl.noStore())
                    .body(new SampleResponse("id-" + count, request.name()));
        }

        @Idempotent(operation = "test-required", required = true)
        public ResponseEntity<SampleResponse> requiredEndpoint(@RequestBody SampleRequest request) {
            invocations.incrementAndGet();
            return ResponseEntity.ok(new SampleResponse("ok", request.name()));
        }

        @Idempotent(operation = "test-path")
        public ResponseEntity<SampleResponse> pathAndOptionalBodyEndpoint(
                @PathVariable UUID uploadId,
                @RequestBody(required = false) SampleRequest request) {
            invocations.incrementAndGet();
            return ResponseEntity.accepted().body(new SampleResponse(uploadId.toString(), "done"));
        }

        @Idempotent(operation = "test-fail")
        public ResponseEntity<SampleResponse> failingEndpoint() {
            throw ApiException.conflict("boom");
        }
    }

    record SampleRequest(String name, long size) {}

    record SampleResponse(String id, String name) {}
}
