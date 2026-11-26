package com.mediamesh.ingestion.features.uploads.presign;

import com.mediamesh.ingestion.storage.PartGeometry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request payload for {@code POST /api/v1/uploads/{uploadId}/parts/presign}.
 */
public record PresignPartsRequest(
        @NotEmpty
        @Size(max = PartGeometry.MAX_PRESIGN_PARTS)
        @Valid
        List<PartPresignSpec> parts) {

    /** Part number and client-computed Base64 CRC32C checksum. */
    public record PartPresignSpec(
            @NotNull @Min(1) Integer partNumber,
            String crc32c) {}
}
