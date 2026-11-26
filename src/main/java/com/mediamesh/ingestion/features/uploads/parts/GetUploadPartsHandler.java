package com.mediamesh.ingestion.features.uploads.parts;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import com.mediamesh.ingestion.config.S3Properties;
import com.mediamesh.ingestion.features.uploads.parts.PartsBatchResponse.UploadedPartDto;
import com.mediamesh.ingestion.storage.PartGeometry;
import com.mediamesh.ingestion.storage.UploadSupport;
import com.mediamesh.ingestion.storage.persistence.StorageObjectEntity;
import com.mediamesh.ingestion.storage.persistence.StorageObjectRepository;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.UploadedPartsPage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lists already-uploaded multipart parts with their stored sizes and CRC32C checksums for resume.
 */
@Service
@RequiredArgsConstructor
public class GetUploadPartsHandler {

    private final StorageObjectRepository storageObjectRepository;
    private final ObjectStorageProvider objectStorageProvider;
    private final S3Properties s3Properties;
    private final TransactionTemplate transactionTemplate;

    public PartsBatchResponse handle(UUID uploadId, UUID userId) {
        return handle(uploadId, userId, null, PartGeometry.MAX_LIST_PARTS_PAGE);
    }

    public PartsBatchResponse handle(
            UUID uploadId, UUID userId, Integer partNumberMarker, int maxParts) {
        if (maxParts < 1 || maxParts > PartGeometry.MAX_LIST_PARTS_PAGE) {
            throw ApiException.of(
                    ErrorCode.VALIDATION_ERROR,
                    "maxParts must be between 1 and " + PartGeometry.MAX_LIST_PARTS_PAGE);
        }

        StorageObjectEntity entity = UploadSupport.requireOwnedUpload(storageObjectRepository, uploadId, userId);

        Instant now = Instant.now();
        UploadSupport.requireUploadAcceptingParts(entity, now);
        UploadSupport.slideUploadExpiration(storageObjectRepository, transactionTemplate, entity, s3Properties, now);

        UploadedPartsPage page = objectStorageProvider.listPartsPage(
                entity.getBucket(),
                entity.getObjectKey(),
                entity.getProviderUploadId(),
                partNumberMarker,
                maxParts);

        List<UploadedPartDto> uploadedParts = page.parts().stream()
                .map(part -> new UploadedPartDto(part.partNumber(), part.sizeBytes(), part.checksumCrc32c()))
                .toList();

        return new PartsBatchResponse(
                uploadId,
                entity.getTotalParts(),
                entity.getPartSize(),
                uploadedParts,
                page.nextPartNumberMarker(),
                page.truncated());
    }
}
