package com.mediamesh.ingestion.features.uploads;

import java.time.Instant;
import java.util.Map;

/**
 * Presigned upload URL and required signed headers for a single multipart part.
 */
public record PresignedPartDto(
        int partNumber,
        String url,
        Map<String, String> headers,
        Instant expiresAt,
        long expectedSizeBytes) {}
