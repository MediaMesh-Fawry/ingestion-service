package com.mediamesh.ingestion.features.uploads.presign;

import com.mediamesh.ingestion.features.uploads.PresignedPartDto;
import java.util.List;

/**
 * Response payload for {@code POST /api/v1/uploads/{uploadId}/parts/presign}.
 */
public record PresignPartsResponse(List<PresignedPartDto> parts) {}
