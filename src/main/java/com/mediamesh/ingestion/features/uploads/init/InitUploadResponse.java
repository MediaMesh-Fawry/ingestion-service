package com.mediamesh.ingestion.features.uploads.init;

import java.time.Instant;
import java.util.UUID;

/**
 * Response payload for {@code POST /api/v1/uploads/init} returning the upload plan.
 */
public record InitUploadResponse(
        UUID uploadId,
        UUID mediaId,
        String status,
        long partSizeBytes,
        int totalParts,
        int recommendedConcurrency,
        Instant expiresAt) {}
