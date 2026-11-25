package com.mediamesh.ingestion.features.uploads.init;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.config.S3Properties;
import com.mediamesh.ingestion.features.uploads.PresignedPartDto;
import com.mediamesh.ingestion.storage.PartGeometry;
import com.mediamesh.ingestion.storage.UploadSupport;
import com.mediamesh.ingestion.storage.persistence.CleanupKind;
import com.mediamesh.ingestion.storage.persistence.StorageCleanupEntity;
import com.mediamesh.ingestion.storage.persistence.StorageCleanupRepository;
import com.mediamesh.ingestion.storage.persistence.StorageObjectEntity;
import com.mediamesh.ingestion.storage.persistence.StorageObjectRepository;
import com.mediamesh.ingestion.storage.persistence.StorageObjectStatus;
import com.mediamesh.ingestion.storage.policy.AccessChecker;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles upload session allocation using {@code TX1 / S3 / TX2} boundaries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InitUploadHandler {

    private static final Duration ALLOCATING_CLEANUP_GRACE = Duration.ofMinutes(10);
    private static final String CHECKSUM_ALGORITHM_CRC32C = "CRC32C";

    private final StorageObjectRepository storageObjectRepository;
    private final StorageCleanupRepository storageCleanupRepository;
    private final ObjectStorageProvider objectStorageProvider;
    private final S3Properties s3Properties;
    private final TransactionTemplate transactionTemplate;
    private final AccessChecker accessChecker;

    public InitUploadResponse handle(UUID userId, InitUploadRequest request) {
        if (!accessChecker.canUpload(userId, request.groupId(), request.libraryId())) {
            throw ApiException.forbidden("User is not permitted to upload to this group or library");
        }

        PartGeometry.validateUploadRequest(
                request.fileSizeBytes(),
                s3Properties.maxObjectSizeBytes(),
                request.contentType());
        PartGeometry.PartPlan plan = PartGeometry.compute(request.fileSizeBytes());

        UUID uploadId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID(); // media service
        String bucket = s3Properties.rawBucket();
        String objectKey = PartGeometry.rawObjectKey(request.groupId(), mediaId);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(s3Properties.uploadTtl());

        transactionTemplate.executeWithoutResult(status -> {
            storageObjectRepository.save(StorageObjectEntity.builder()
                    .uploadId(uploadId)
                    .mediaId(mediaId)
                    .groupId(request.groupId())
                    .libraryId(request.libraryId())
                    .userId(userId)
                    .fileName(request.fileName())
                    .title(request.title())
                    .bucket(bucket)
                    .objectKey(objectKey)
                    .status(StorageObjectStatus.ALLOCATING)
                    .declaredSize(request.fileSizeBytes())
                    .partSize(plan.partSizeBytes())
                    .totalParts(plan.totalParts())
                    .contentType(request.contentType())
                    .checksumAlgorithm(s3Properties.fullObjectChecksumEnabled() ? CHECKSUM_ALGORITHM_CRC32C : null)
                    .expiresAt(expiresAt)
                    .build());

            storageCleanupRepository.save(StorageCleanupEntity.builder()
                    .kind(CleanupKind.ABORT_MP)
                    .bucket(bucket)
                    .objectKey(objectKey)
                    .providerUploadId(null)
                    .attempts(0)
                    .runAfter(now.plus(ALLOCATING_CLEANUP_GRACE))
                    .build());
        });

        String providerUploadId;
        try {
            providerUploadId = objectStorageProvider.createMultipart(bucket, objectKey, request.contentType());
        } catch (RuntimeException ex) {
            transactionTemplate.executeWithoutResult(status -> storageObjectRepository.transitionStatus(
                    uploadId,
                    List.of(StorageObjectStatus.ALLOCATING),
                    StorageObjectStatus.FAILED,
                    Instant.now()));
            throw ex;
        }

        transactionTemplate.executeWithoutResult(status -> {
            int updated = storageObjectRepository.markPendingWithProviderUploadId(
                    uploadId, providerUploadId, Instant.now());
            if (updated == 1) {
                storageCleanupRepository.deletePlaceholderAbort(bucket, objectKey);
            }
        });

        int initialPresignCount = Math.min(PartGeometry.INIT_PRESIGN_CAP, plan.totalParts());
        List<PresignedPartDto> initialParts = UploadSupport.presignRange(
                objectStorageProvider,
                s3Properties,
                bucket,
                objectKey,
                providerUploadId,
                request.fileSizeBytes(),
                plan.partSizeBytes(),
                plan.totalParts(),
                1,
                initialPresignCount);

        log.info(
                "Allocated uploadId={} mediaId={} totalParts={} partSizeBytes={}",
                uploadId,
                mediaId,
                plan.totalParts(),
                plan.partSizeBytes());

        return new InitUploadResponse(
                uploadId,
                mediaId,
                StorageObjectStatus.PENDING.toOpenApiStatus(),
                plan.partSizeBytes(),
                plan.totalParts(),
                expiresAt,
                initialParts);
    }
}
