package com.mediamesh.ingestion.features.uploads.parts;

import com.mediamesh.ingestion.features.uploads.PresignedPartDto;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Response payload for {@code GET /api/v1/uploads/{uploadId}/parts}.
 */
public record PartsBatchResponse(
        UUID uploadId,
        int totalParts,
        long partSizeBytes,
        Set<Integer> uploadedParts,
        List<PresignedPartDto> parts) {}
