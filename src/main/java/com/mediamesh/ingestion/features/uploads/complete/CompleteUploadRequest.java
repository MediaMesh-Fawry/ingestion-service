package com.mediamesh.ingestion.features.uploads.complete;

import jakarta.validation.constraints.Size;

/**
 * Optional request payload for {@code POST /api/v1/uploads/{uploadId}/complete}.
 * Client-supplied full-object CRC32C checksum is verified against
 * authoritative object storage state when present.
 */
public record CompleteUploadRequest(
                @Size(max = 128) String expectedChecksum) {
}
