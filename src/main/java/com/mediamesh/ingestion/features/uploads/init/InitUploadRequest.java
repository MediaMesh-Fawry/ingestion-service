package com.mediamesh.ingestion.features.uploads.init;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request payload for {@code POST /api/v1/uploads/init}.
 */
public record InitUploadRequest(
        @NotNull UUID groupId,
        @NotNull UUID libraryId,
        @NotBlank @Size(max = 255) String fileName,
        @Size(max = 255) String title,
        @NotBlank @Size(max = 128) String contentType,
        @Positive long fileSizeBytes,
        @Size(max = 128) String expectedChecksum) {

    public InitUploadRequest(
            UUID groupId,
            UUID libraryId,
            String fileName,
            String title,
            String contentType,
            long fileSizeBytes) {
        this(groupId, libraryId, fileName, title, contentType, fileSizeBytes, null);
    }
}
