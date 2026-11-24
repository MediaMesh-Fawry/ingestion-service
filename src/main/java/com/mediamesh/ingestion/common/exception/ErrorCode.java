package com.mediamesh.ingestion.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Canonical machine-readable error codes for the MediaMesh Ingestion &amp; Storage Service.
 * Each code is paired with the HTTP status returned to callers and the API Gateway.
 */
public enum ErrorCode {

    // Gateway identity & authorization
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN"),

    // Request & validation
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED"),
    MALFORMED_JSON(HttpStatus.BAD_REQUEST, "MALFORMED_JSON"),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE"),

    // Storage lifecycle & verification
    UPLOAD_NOT_FOUND(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND"),
    MEDIA_NOT_FOUND(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND"),
    INVALID_UPLOAD_STATE(HttpStatus.CONFLICT, "INVALID_UPLOAD_STATE"),
    UPLOAD_IN_PROGRESS(HttpStatus.TOO_EARLY, "UPLOAD_IN_PROGRESS"),
    UPLOAD_EXPIRED(HttpStatus.GONE, "UPLOAD_EXPIRED"),
    SIZE_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "SIZE_MISMATCH"),
    PARTS_INCOMPLETE(HttpStatus.UNPROCESSABLE_CONTENT, "PARTS_INCOMPLETE"),
    STORAGE_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "STORAGE_PROVIDER_ERROR"),

    // Quotas, idempotency & rate limiting
    QUOTA_EXCEEDED(HttpStatus.INSUFFICIENT_STORAGE, "QUOTA_EXCEEDED"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT"),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED"),

    // Generic HTTP & fallback
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR");

    private final HttpStatus status;
    private final String code;

    ErrorCode(HttpStatus status, String code) {
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
