package com.mediamesh.ingestion.storage;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.config.S3Properties;
import com.mediamesh.ingestion.features.uploads.PresignedPartDto;
import com.mediamesh.ingestion.storage.persistence.StorageObjectEntity;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.PresignedPartUrl;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared helpers reused across upload handlers.
 */
public final class UploadSupport {

    private UploadSupport() {
    }

    /**
     * Ensures the requesting user owns the upload session, failing closed with 403.
     */
    public static void requireOwner(StorageObjectEntity entity, UUID userId) {
        if (!entity.getUserId().equals(userId)) {
            throw ApiException.forbidden("Upload does not belong to the requesting user");
        }
    }

    /**
     * Generates presigned part URLs for an inclusive 1-based part range,
     * signing each URL with its exact expected {@code Content-Length}.
     */
    public static List<PresignedPartDto> presignRange(
            ObjectStorageProvider objectStorageProvider,
            S3Properties s3Properties,
            String bucket,
            String objectKey,
            String providerUploadId,
            long declaredSizeBytes,
            long partSizeBytes,
            int totalParts,
            int fromPart,
            int toPart) {
        List<PresignedPartDto> result = new ArrayList<>(toPart - fromPart + 1);
        for (int partNumber = fromPart; partNumber <= toPart; partNumber++) {
            long expectedSize = PartGeometry.expectedPartSize(
                    declaredSizeBytes, partSizeBytes, totalParts, partNumber);
            PresignedPartUrl presigned = objectStorageProvider.presignPart(
                    bucket, objectKey, providerUploadId, partNumber, expectedSize, s3Properties.presignTtl());
            result.add(new PresignedPartDto(partNumber, presigned.url(), presigned.expiresAt(), expectedSize));
        }
        return result;
    }
}
