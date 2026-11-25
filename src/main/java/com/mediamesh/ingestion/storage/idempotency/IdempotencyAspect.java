package com.mediamesh.ingestion.storage.idempotency;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import com.mediamesh.ingestion.security.IdentityHeaderResolver;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring AOP aspect enforcing HTTP {@code Idempotency-Key} deduplication on
 * controller methods annotated with {@link Idempotent}.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotencyAspect {

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @Around("@annotation(idempotent)")
    public Object aroundIdempotent(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        HttpServletRequest request = currentHttpRequest();
        String idempotencyKey = request.getHeader(idempotent.headerName());

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            if (idempotent.required()) {
                throw new ApiException(
                        ErrorCode.VALIDATION_ERROR,
                        "Missing required header: " + idempotent.headerName());
            }
            return joinPoint.proceed();
        }

        UUID userId = IdentityHeaderResolver.parseUserId(
                request.getHeader(IdentityHeaderResolver.USER_ID_HEADER));

        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object fingerprintPayload = extractFingerprintPayload(method, joinPoint.getArgs());

        try {
            if (ResponseEntity.class.isAssignableFrom(method.getReturnType())) {
                CachedHttpResponse cached = idempotencyService.execute(
                        idempotent.operation(),
                        userId,
                        idempotencyKey,
                        fingerprintPayload,
                        CachedHttpResponse.class,
                        () -> toCachedHttpResponse((ResponseEntity<?>) proceedUnchecked(joinPoint)));
                return toResponseEntity(cached, method);
            }

            return executeTyped(
                    joinPoint,
                    idempotent.operation(),
                    userId,
                    idempotencyKey,
                    fingerprintPayload,
                    method.getReturnType());
        } catch (UncheckedExecutionException ex) {
            throw ex.getCause();
        }
    }

    private <T> T executeTyped(
            ProceedingJoinPoint joinPoint,
            String operation,
            UUID userId,
            String idempotencyKey,
            Object fingerprintPayload,
            Class<T> returnType) {
        return idempotencyService.execute(
                operation,
                userId,
                idempotencyKey,
                fingerprintPayload,
                returnType,
                () -> returnType.cast(proceedUnchecked(joinPoint)));
    }

    private HttpServletRequest currentHttpRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        throw new IllegalStateException("@Idempotent requires an active HTTP servlet request");
    }

    private Object proceedUnchecked(ProceedingJoinPoint joinPoint) {
        try {
            return joinPoint.proceed();
        } catch (Throwable ex) {
            if (ex instanceof RuntimeException runtimeEx) {
                throw runtimeEx;
            }
            if (ex instanceof Error error) {
                throw error;
            }
            throw new UncheckedExecutionException(ex);
        }
    }

    private Object extractFingerprintPayload(Method method, Object[] args) {
        Parameter[] parameters = method.getParameters();
        Map<String, Object> payload = new LinkedHashMap<>();

        for (int i = 0; i < parameters.length && i < args.length; i++) {
            Parameter param = parameters[i];
            Object arg = args[i];
            if (arg instanceof ServletRequest || arg instanceof ServletResponse) {
                continue;
            }

            PathVariable pathVariable = param.getAnnotation(PathVariable.class);
            if (pathVariable != null) {
                String name = resolveName(pathVariable.value(), pathVariable.name(), param, i);
                payload.put("path:" + name, arg);
                continue;
            }

            RequestParam requestParam = param.getAnnotation(RequestParam.class);
            if (requestParam != null) {
                String name = resolveName(requestParam.value(), requestParam.name(), param, i);
                payload.put("param:" + name, arg);
                continue;
            }

            if (param.isAnnotationPresent(RequestBody.class)) {
                payload.put("body", arg);
            }
        }

        return payload;
    }

    private static String resolveName(String value, String name, Parameter param, int index) {
        if (value != null && !value.isBlank()) {
            return value;
        }
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (param.isNamePresent()) {
            return param.getName();
        }
        return "arg" + index;
    }

    private CachedHttpResponse toCachedHttpResponse(ResponseEntity<?> responseEntity) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        responseEntity.getHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));

        String bodyJson = null;
        if (responseEntity.getBody() != null) {
            try {
                bodyJson = objectMapper.writeValueAsString(responseEntity.getBody());
            } catch (JacksonException ex) {
                throw new IllegalStateException("Failed to serialize idempotency response body", ex);
            }
        }

        return new CachedHttpResponse(responseEntity.getStatusCode().value(), headers, bodyJson);
    }

    private ResponseEntity<Object> toResponseEntity(CachedHttpResponse cached, Method method) {
        HttpHeaders httpHeaders = new HttpHeaders();
        if (cached.headers() != null) {
            cached.headers().forEach(httpHeaders::addAll);
        }

        Object body = null;
        if (cached.bodyJson() != null) {
            Class<?> bodyType = resolveResponseBodyType(method);
            if (bodyType != null && bodyType != Void.class && bodyType != void.class) {
                try {
                    body = objectMapper.readValue(cached.bodyJson(), bodyType);
                } catch (JacksonException ex) {
                    throw new IllegalStateException("Failed to deserialize cached idempotency response body", ex);
                }
            }
        }

        return ResponseEntity.status(cached.statusCode())
                .headers(httpHeaders)
                .body(body);
    }

    private static Class<?> resolveResponseBodyType(Method method) {
        Class<?> resolved = ResolvableType.forMethodReturnType(method).getGeneric(0).resolve();
        return resolved != null ? resolved : Object.class;
    }

    private record CachedHttpResponse(int statusCode, Map<String, List<String>> headers, String bodyJson) {}

    private static final class UncheckedExecutionException extends RuntimeException {
        private UncheckedExecutionException(Throwable cause) {
            super(cause);
        }
    }
}
