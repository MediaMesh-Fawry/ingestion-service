package com.mediamesh.ingestion.features.uploads.parts;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import com.mediamesh.ingestion.config.S3Properties;
import com.mediamesh.ingestion.features.uploads.PresignedPartDto;
import com.mediamesh.ingestion.storage.PartGeometry;
import com.mediamesh.ingestion.storage.UploadSupport;
import com.mediamesh.ingestion.storage.persistence.StorageObjectEntity;
import com.mediamesh.ingestion.storage.persistence.StorageObjectRepository;
import com.mediamesh.ingestion.storage.persistence.StorageObjectStatus;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.UploadedPart;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles resumable presigned part batch requests and slides upload expiration.
 */
@Service
@RequiredArgsConstructor
public class GetUploadPartsHandler {

    private final StorageObjectRepository storageObjectRepository;
    private final ObjectStorageProvider objectStorageProvider;
    private final S3Properties s3Properties;
    private final TransactionTemplate transactionTemplate;

    public PartsBatchResponse handle(UUID uploadId, UUID userId, int fromPart, int toPart) {
        return handle(uploadId, userId, fromPart, toPart, true);
    }

    public PartsBatchResponse handle(
            UUID uploadId, UUID userId, int fromPart, int toPart, boolean includeUploaded) {
        validatePartRange(fromPart, toPart);

        StorageObjectEntity entity = UploadSupport.requireOwnedUpload(storageObjectRepository, uploadId, userId);

        Instant now = Instant.now();
        validateUploadAcceptingParts(entity, toPart, now);

        Instant maxExpiresAt = entity.getCreatedAt().plus(s3Properties.maxUploadLifetime());
        Instant candidateExpiresAt = now.plus(s3Properties.uploadTtl());
        Instant newExpiresAt = candidateExpiresAt.isAfter(maxExpiresAt) ? maxExpiresAt : candidateExpiresAt; // min
                                                                                                             // maxExpiresAt,
                                                                                                             // candidateExpiresAt
        transactionTemplate.executeWithoutResult(
                status -> storageObjectRepository.slideExpiresAt(uploadId, newExpiresAt, now));

        Set<Integer> uploadedPartNumbers = includeUploaded
                ? objectStorageProvider.listParts(
                                entity.getBucket(), entity.getObjectKey(), entity.getProviderUploadId())
                        .stream()
                        .map(UploadedPart::partNumber)
                        .collect(Collectors.toSet())
                : Set.of();

        List<PresignedPartDto> presigned = UploadSupport.presignRange(
                objectStorageProvider,
                s3Properties,
                entity.getBucket(),
                entity.getObjectKey(),
                entity.getProviderUploadId(),
                entity.getDeclaredSize(),
                entity.getPartSize(),
                entity.getTotalParts(),
                fromPart,
                toPart);

        return new PartsBatchResponse(
                uploadId,
                entity.getTotalParts(),
                entity.getPartSize(),
                uploadedPartNumbers,
                presigned);
    }

    private void validatePartRange(int fromPart, int toPart) {
        if (fromPart < 1 || fromPart > toPart) {
            throw ApiException.of(
                    ErrorCode.VALIDATION_ERROR, "Part range must satisfy 1 <= from <= to");
        }
        if (toPart - fromPart + 1 > PartGeometry.MAX_PARTS_BATCH) {
            throw ApiException.of(
                    ErrorCode.VALIDATION_ERROR,
                    "Requested part batch exceeds maximum of " + PartGeometry.MAX_PARTS_BATCH + " parts");
        }
    }

    private void validateUploadAcceptingParts(StorageObjectEntity entity, int toPart, Instant now) {
        if (entity.getStatus() == StorageObjectStatus.EXPIRED
                || entity.getStatus() == StorageObjectStatus.ABORTED
                || (entity.getStatus() == StorageObjectStatus.PENDING && entity.getExpiresAt().isBefore(now))) {
            throw ApiException.gone("Upload session is no longer active");
        }
        if (entity.getStatus() != StorageObjectStatus.PENDING) {
            throw ApiException.conflict("Upload is not accepting parts in state: " + entity.getStatus());
        }
        if (toPart > entity.getTotalParts()) {
            throw ApiException.of(
                    ErrorCode.VALIDATION_ERROR,
                    "Requested part " + toPart + " exceeds totalParts " + entity.getTotalParts());
        }
    }
}
