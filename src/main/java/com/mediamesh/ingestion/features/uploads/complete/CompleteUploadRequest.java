package com.mediamesh.ingestion.features.uploads.complete;

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Optional request payload for {@code POST /api/v1/uploads/{uploadId}/complete}.
 * Client-supplied part ETags and full-object CRC32C checksum are verified against
 * authoritative object storage state.
 */
public record CompleteUploadRequest(
        List<SubmittedPartDto> parts,
        @Size(max = 128) String expectedChecksum) {

    public CompleteUploadRequest(List<SubmittedPartDto> parts) {
        this(parts, null);
    }

    public record SubmittedPartDto(int partNumber, String etag) {}
}
