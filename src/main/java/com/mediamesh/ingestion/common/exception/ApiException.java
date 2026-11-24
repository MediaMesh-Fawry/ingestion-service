package com.mediamesh.ingestion.common.exception;

import org.springframework.http.HttpStatus;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * Domain/application exception carrying a canonical {@link ErrorCode},
 * human-readable message, and optional field-level validation details.
 * Caught by {@link GlobalExceptionHandler} and serialized into {@link ErrorResponse}.
 */
public class ApiException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final transient List<ValidationErrorDetail> details;

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public ApiException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, null, cause);
    }

    public ApiException(ErrorCode errorCode, String message, List<ValidationErrorDetail> details) {
        this(errorCode, message, details, null);
    }

    private ApiException(ErrorCode errorCode, String message, List<ValidationErrorDetail> details, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        this.details = details == null ? null : List.copyOf(details);
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
        return new ApiException(ErrorCode.UPLOAD_NOT_FOUND, "Upload not found: " + uploadId);
    }

    public static ApiException mediaNotFound(Object mediaId) {
        return new ApiException(ErrorCode.MEDIA_NOT_FOUND, "Media not found: " + mediaId);
    }

    public static ApiException invalidUploadState(String message) {
        return new ApiException(ErrorCode.INVALID_UPLOAD_STATE, message);
    }

    public static ApiException uploadInProgress(String message) {
        return new ApiException(ErrorCode.UPLOAD_IN_PROGRESS, message);
    }

    public static ApiException sizeMismatch(long declaredSize, long actualSize) {
        return new ApiException(
                ErrorCode.SIZE_MISMATCH,
                "Uploaded object size (" + actualSize + ") does not match declared size (" + declaredSize + ").");
    }

    public static ApiException partsIncomplete(String message) {
        return new ApiException(ErrorCode.PARTS_INCOMPLETE, message);
    }

    public static ApiException storageProviderError(String message, Throwable cause) {
        return new ApiException(ErrorCode.STORAGE_PROVIDER_ERROR, message, cause);
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
}
