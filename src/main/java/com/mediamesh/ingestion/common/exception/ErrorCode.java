package com.mediamesh.ingestion.common.exception;

import org.springframework.http.HttpStatus;

import java.net.URI;

/**
 * Canonical machine-readable error codes matching the OpenAPI {@code ProblemDetail.code} contract.
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation Error", "validation-error"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Unauthorized", "unauthorized"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "Forbidden", "forbidden"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not Found", "not-found"),
    CONFLICT(HttpStatus.CONFLICT, "CONFLICT", "Conflict", "conflict"),
    GONE(HttpStatus.GONE, "GONE", "Gone", "gone"),
    QUOTA_EXCEEDED(HttpStatus.CONTENT_TOO_LARGE, "QUOTA_EXCEEDED", "Quota Exceeded", "quota-exceeded"),
    MAX_OBJECT_SIZE_EXCEEDED(HttpStatus.CONTENT_TOO_LARGE, "MAX_OBJECT_SIZE_EXCEEDED",
            "Max Object Size Exceeded", "max-object-size-exceeded"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED",
            "Idempotency Key Reused", "idempotency-key-reused"),
    SIZE_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "SIZE_MISMATCH", "Size Mismatch", "size-mismatch"),
    CHECKSUM_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "CHECKSUM_MISMATCH",
            "Checksum Mismatch", "checksum-mismatch"),
    PARTS_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "PARTS_MISSING", "Parts Missing", "parts-missing"),
    INVALID_MEDIA_CONTAINER(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_MEDIA_CONTAINER",
            "Invalid Media Container", "invalid-media-container"),
    COMMIT_IN_PROGRESS(HttpStatus.TOO_EARLY, "COMMIT_IN_PROGRESS", "Commit In Progress", "commit-in-progress"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Rate Limited", "rate-limited"),
    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE",
            "Storage Unavailable", "storage-unavailable"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
            "Method Not Allowed", "method-not-allowed"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
            "Internal Server Error", "internal-server-error");

    private static final String PROBLEM_TYPE_PREFIX = "https://mediamesh.local/problems/";

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final URI type;

    ErrorCode(HttpStatus status, String code, String title, String slug) {
        this.status = status;
        this.code = code;
        this.title = title;
        this.type = URI.create(PROBLEM_TYPE_PREFIX + slug);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public URI getType() {
        return type;
    }
}
