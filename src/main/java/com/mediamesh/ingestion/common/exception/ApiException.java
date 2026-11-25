package com.mediamesh.ingestion.common.exception;

import org.springframework.http.HttpStatus;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * Domain/application exception carrying a canonical {@link ErrorCode},
 * human-readable detail message, optional field-level validation details,
 * and optional {@code Retry-After} seconds.
 */
public class ApiException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final transient List<ValidationErrorDetail> details;
    private final Integer retryAfterSeconds;

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, null, null);
    }

    public ApiException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, null, null, cause);
    }

    public ApiException(ErrorCode errorCode, String message, List<ValidationErrorDetail> details) {
        this(errorCode, message, details, null, null);
    }

    public ApiException(ErrorCode errorCode, String message, Integer retryAfterSeconds) {
        this(errorCode, message, null, retryAfterSeconds, null);
    }

    private ApiException(
            ErrorCode errorCode,
            String message,
            List<ValidationErrorDetail> details,
            Integer retryAfterSeconds,
            Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        this.details = details == null ? null : List.copyOf(details);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiException of(ErrorCode errorCode, String message) {
        return new ApiException(errorCode, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(ErrorCode.UNAUTHORIZED, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ErrorCode.FORBIDDEN, message);
    }

    public static ApiException uploadNotFound(Object uploadId) {
        return new ApiException(ErrorCode.NOT_FOUND, "Upload not found: " + uploadId);
    }

    public static ApiException mediaNotFound(Object mediaId) {
        return new ApiException(ErrorCode.NOT_FOUND, "Media not found: " + mediaId);
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }

    public static ApiException gone(String message) {
        return new ApiException(ErrorCode.GONE, message);
    }

    public static ApiException maxObjectSizeExceeded(long declaredSize, long maxSize) {
        return new ApiException(
                ErrorCode.MAX_OBJECT_SIZE_EXCEEDED,
                "Declared file size (" + declaredSize + " bytes) exceeds maximum allowed object size ("
                        + maxSize + " bytes).");
    }

    public static ApiException commitInProgress(int retryAfterSeconds) {
        return new ApiException(
                ErrorCode.COMMIT_IN_PROGRESS,
                "Upload commit is already in progress; retry shortly.",
                retryAfterSeconds);
    }

    public static ApiException sizeMismatch(long declaredSize, long actualSize) {
        return new ApiException(
                ErrorCode.SIZE_MISMATCH,
                "Uploaded object size (" + actualSize + ") does not match declared size (" + declaredSize + ").");
    }

    public static ApiException checksumMismatch(String message) {
        return new ApiException(ErrorCode.CHECKSUM_MISMATCH, message);
    }

    public static ApiException partsMissing(String message) {
        return new ApiException(ErrorCode.PARTS_MISSING, message);
    }

    public static ApiException storageUnavailable(String message, Throwable cause) {
        return new ApiException(ErrorCode.STORAGE_UNAVAILABLE, message, cause);
    }

    public static ApiException idempotencyKeyReused(String message) {
        return new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, message);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return errorCode.getStatus();
    }

    public String getCode() {
        return errorCode.getCode();
    }

    public List<ValidationErrorDetail> getDetails() {
        return details;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
