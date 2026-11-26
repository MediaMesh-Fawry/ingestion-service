package com.mediamesh.ingestion.features.uploads.parts;

import java.util.List;
import java.util.UUID;

/**
 * Response payload for {@code GET /api/v1/uploads/{uploadId}/parts} listing uploaded parts and CRCs.
 */
public record PartsBatchResponse(
        UUID uploadId,
        int totalParts,
        long partSizeBytes,
        List<UploadedPartDto> uploadedParts,
        Integer nextPartNumberMarker,
        boolean truncated) {

    /** Metadata for a single already-uploaded part stored in object storage. */
    public record UploadedPartDto(int partNumber, long size, String crc32c) {}
}
