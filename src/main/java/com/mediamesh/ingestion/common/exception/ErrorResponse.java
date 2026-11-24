package com.mediamesh.ingestion.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Standardized JSON error envelope returned by all ingestion API error handlers.
 * Includes a machine-readable {@code code}, human-readable {@code message},
 * optional {@code details} for field validation errors, the {@code requestId}
 * correlation token, and an ISO-8601 {@code timestamp}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        List<ValidationErrorDetail> details,
        String requestId,
        Instant timestamp) {

    public ErrorResponse(String code, String message, String requestId) {
        this(code, message, null, requestId, Instant.now());
    }

    public ErrorResponse(String code, String message, List<ValidationErrorDetail> details, String requestId) {
        this(code, message, details, requestId, Instant.now());
    }
}
