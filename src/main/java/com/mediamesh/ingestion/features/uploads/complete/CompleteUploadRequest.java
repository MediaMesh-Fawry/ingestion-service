package com.mediamesh.ingestion.features.uploads.complete;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request payload for {@code POST /api/v1/uploads/{uploadId}/complete}.
 * Requires the client-computed full-object CRC32C and all per-part CRC32C checksums.
 */
public record CompleteUploadRequest(
        @Size(max = 128) String expectedChecksum,
        @Valid List<PartChecksumDto> parts) {

    /** 1-based part number and its client-computed Base64 CRC32C checksum. */
    public record PartChecksumDto(
            @NotNull @Min(1) Integer partNumber,
            String crc32c) {}
}
