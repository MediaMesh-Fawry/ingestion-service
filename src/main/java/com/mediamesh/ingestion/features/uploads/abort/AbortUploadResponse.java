package com.mediamesh.ingestion.features.uploads.abort;

import java.util.UUID;

/**
 * Response payload for {@code POST /api/v1/uploads/{uploadId}/abort}.
 */
public record AbortUploadResponse(UUID uploadId, String status) {}
