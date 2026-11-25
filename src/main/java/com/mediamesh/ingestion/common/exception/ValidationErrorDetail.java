package com.mediamesh.ingestion.common.exception;

/**
 * Field-level validation failure included in RFC 7807 {@code ProblemDetail.errors} for 400 responses.
 */
public record ValidationErrorDetail(
        String field,
        String message) {
}
