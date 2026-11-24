package com.mediamesh.ingestion.common.exception;

/**
 * Represents a single field-level validation failure inside
 * {@link ErrorResponse#details()}.
 */
public record ValidationErrorDetail(
        String field,
        String code,
        String message) {
}
