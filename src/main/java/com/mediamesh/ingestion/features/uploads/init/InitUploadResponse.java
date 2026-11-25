package com.mediamesh.ingestion.features.uploads.init;

import com.mediamesh.ingestion.features.uploads.PresignedPartDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response payload for {@code POST /api/v1/uploads/init}.
 */
public record InitUploadResponse(
        UUID uploadId,
        UUID mediaId,
        String status,
        long partSizeBytes,
        int totalParts,
        Instant expiresAt,
        List<PresignedPartDto> parts) {}
