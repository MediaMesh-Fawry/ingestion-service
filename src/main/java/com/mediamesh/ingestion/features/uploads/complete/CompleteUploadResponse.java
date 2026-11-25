package com.mediamesh.ingestion.features.uploads.complete;

import java.util.UUID;

/**
 * Response payload for {@code POST /api/v1/uploads/{uploadId}/complete}.
 */
public record CompleteUploadResponse(
        UUID uploadId,
        UUID mediaId,
        String status,
        long verifiedSizeBytes,
        String checksum,
        String etag) {}
