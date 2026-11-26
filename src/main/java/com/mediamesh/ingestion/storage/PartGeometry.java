package com.mediamesh.ingestion.storage;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import java.util.Locale;
import java.util.UUID;

/**
 * Multipart geometry calculator, upload request validator, and opaque S3 key
 * generator.
 */
public final class PartGeometry {

    public static final long MIN_PART_SIZE_BYTES = 8L * 1024 * 1024;
    public static final int MAX_PARTS = 10_000;
    public static final int MAX_PRESIGN_PARTS = 8;
    public static final int MAX_LIST_PARTS_PAGE = 1000;
    private static final long MIB = 1024L * 1024L;

    private PartGeometry() {
    }

    /**
     * Validates declared size and MIME content type against service limits and
     * media allowlist.
     */
    public static void validateUploadRequest(
            long declaredSizeBytes, long maxObjectSizeBytes, String contentType) {
        if (declaredSizeBytes <= 0) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "fileSizeBytes must be greater than 0");
        }
        if (declaredSizeBytes > maxObjectSizeBytes) {
            throw ApiException.maxObjectSizeExceeded(declaredSizeBytes, maxObjectSizeBytes);
        }
        if (contentType == null || contentType.isBlank()) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "contentType must not be blank");
        }
        String normalized = normalizeMimeType(contentType);
        if (!isAllowedMediaMimeType(normalized)) {
            throw ApiException.of(
                    ErrorCode.INVALID_MEDIA_CONTAINER,
                    "Unsupported media content type: " + contentType);
        }
    }

    /**
     * Computes an 8 MiB-aligned part size and total part count capped at
     * {@value #MAX_PARTS}.
     */
    public static PartPlan compute(long declaredSizeBytes) {
        if (declaredSizeBytes <= 0) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "fileSizeBytes must be greater than 0");
        }
        long rawPartSize = Math.max(MIN_PART_SIZE_BYTES, Math.ceilDiv(declaredSizeBytes, MAX_PARTS));
        long partSizeBytes = Math.ceilDiv(rawPartSize, MIN_PART_SIZE_BYTES) * MIN_PART_SIZE_BYTES;
        int totalParts = (int) Math.ceilDiv(declaredSizeBytes, partSizeBytes);
        return new PartPlan(partSizeBytes, totalParts);
    }

    /**
     * Returns the exact expected byte length for a 1-based {@code partNumber}.
     */
    public static long expectedPartSize(
            long declaredSizeBytes, long partSizeBytes, int totalParts, int partNumber) {
        if (partNumber < 1 || partNumber > totalParts) {
            throw ApiException.of(
                    ErrorCode.VALIDATION_ERROR,
                    "Part number " + partNumber + " out of range 1.." + totalParts);
        }
        if (partNumber < totalParts) {
            return partSizeBytes;
        }
        return declaredSizeBytes - ((long) (totalParts - 1) * partSizeBytes);
    }

    /**
     * Generates an opaque raw object key without ever embedding user-supplied
     * filenames.
     */
    public static String rawObjectKey(UUID groupId, UUID mediaId) {
        return "raw/" + groupId + "/" + mediaId;
    }

    /**
     * Generates the processed-outputs prefix for a media item.
     */
    public static String processedPrefix(UUID groupId, UUID mediaId) {
        return "processed/" + groupId + "/" + mediaId + "/";
    }

    private static String normalizeMimeType(String contentType) {
        int semicolon = contentType.indexOf(';');
        String base = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isAllowedMediaMimeType(String mime) {
        return mime.startsWith("video/")
                || mime.startsWith("audio/")
                || mime.startsWith("image/");
    }

    /**
     * Returns recommended client upload concurrency based on part size to bound browser memory.
     */
    public static int recommendedConcurrency(long partSizeBytes) {
        if (partSizeBytes <= 64L * MIB) {
            return 4;
        }
        if (partSizeBytes <= 256L * MIB) {
            return 2;
        }
        return 1;
    }

    /** Computed multipart geometry. */
    public record PartPlan(long partSizeBytes, int totalParts) {
    }
}
