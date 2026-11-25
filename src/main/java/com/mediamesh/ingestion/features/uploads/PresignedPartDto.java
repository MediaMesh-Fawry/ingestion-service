package com.mediamesh.ingestion.features.uploads;

import java.time.Instant;

/**
 * Presigned upload URL for a single multipart part with its exact expected byte length.
 */
public record PresignedPartDto(int partNumber, String url, Instant expiresAt, long expectedSizeBytes) {}
