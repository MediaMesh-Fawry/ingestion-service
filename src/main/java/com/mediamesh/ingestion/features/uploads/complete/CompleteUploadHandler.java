package com.mediamesh.ingestion.features.uploads.complete;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import com.mediamesh.ingestion.config.S3Properties;
import com.mediamesh.ingestion.features.uploads.complete.CompleteUploadRequest.PartChecksumDto;
import com.mediamesh.ingestion.storage.PartGeometry;
import com.mediamesh.ingestion.storage.UploadSupport;
import com.mediamesh.ingestion.storage.events.EventPublisher;
import com.mediamesh.ingestion.storage.events.EventPublisher.MediaUploadedEvent;
import com.mediamesh.ingestion.storage.persistence.CleanupKind;
import com.mediamesh.ingestion.storage.persistence.StorageCleanupEntity;
import com.mediamesh.ingestion.storage.persistence.StorageCleanupRepository;
import com.mediamesh.ingestion.storage.persistence.StorageObjectEntity;
import com.mediamesh.ingestion.storage.persistence.StorageObjectRepository;
import com.mediamesh.ingestion.storage.persistence.StorageObjectStatus;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.CompletedPartSpec;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.UploadedPart;
import com.mediamesh.ingestion.storage.verification.Crc32cCombiner;
import com.mediamesh.ingestion.storage.verification.Crc32cCombiner.PartChecksum;
import com.mediamesh.ingestion.storage.verification.ObjectIntegrityVerifier;
import com.mediamesh.ingestion.storage.verification.ObjectIntegrityVerifier.VerifiedObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles upload verification and atomic commit using {@code TX1 / S3 / TX2} boundaries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompleteUploadHandler {

    private static final int COMMIT_RETRY_AFTER_SECONDS = 2;
    private static final int MAX_CHECKSUM_MISMATCH_RETRIES = 3;

    private final StorageObjectRepository storageObjectRepository;
    private final StorageCleanupRepository storageCleanupRepository;
    private final ObjectStorageProvider objectStorageProvider;
    private final ObjectIntegrityVerifier objectIntegrityVerifier;
    private final S3Properties s3Properties;
    private final TransactionTemplate transactionTemplate;
    private final EventPublisher eventPublisher;

    public CompleteUploadResponse handle(UUID uploadId, UUID userId, CompleteUploadRequest request) {
        if (request == null) {
            throw ApiException.checksumRequired("expectedChecksum and parts are required");
        }
        String expectedChecksum = Crc32cCombiner.requireValidBase64(
                request.expectedChecksum(), "expectedChecksum");
        if (request.parts() == null || request.parts().isEmpty()) {
            throw ApiException.checksumRequired("parts checksum list is required");
        }

        StorageObjectEntity entity = UploadSupport.requireOwnedUpload(storageObjectRepository, uploadId, userId);
        Map<Integer, String> clientPartChecksums = validateClientPartChecksums(
                request.parts(), entity.getTotalParts());

        Instant now = Instant.now();
        boolean claimed = Boolean.TRUE.equals(transactionTemplate.execute(status ->
                storageObjectRepository.claimForCommit(uploadId, expectedChecksum, now) == 1));

        if (!claimed) {
            StorageObjectEntity current = storageObjectRepository.findById(uploadId)
                    .orElseThrow(() -> ApiException.uploadNotFound(uploadId));
            if (current.getStatus() == StorageObjectStatus.PENDING && !current.getExpiresAt().isAfter(now)) {
                throw ApiException.gone("Upload session has expired");
            }
            switch (current.getStatus()) {
                case COMMITTING -> throw ApiException.commitInProgress(COMMIT_RETRY_AFTER_SECONDS);
                case EXPIRED, ABORTED -> throw ApiException.gone(
                        "Upload cannot be committed in state: " + current.getStatus());
                default -> throw ApiException.conflict(
                        "Upload cannot be committed in state: " + current.getStatus());
            }
        }

        List<UploadedPart> s3Parts;
        try {
            s3Parts = objectStorageProvider.listParts(
                    entity.getBucket(), entity.getObjectKey(), entity.getProviderUploadId());
        } catch (RuntimeException ex) {
            rollbackToPending(uploadId);
            throw ex;
        }

        List<CompletedPartSpec> specs = verifyUploadedParts(
                entity, s3Parts, clientPartChecksums, expectedChecksum);

        try {
            objectStorageProvider.completeMultipart(
                    entity.getBucket(),
                    entity.getObjectKey(),
                    entity.getProviderUploadId(),
                    specs,
                    expectedChecksum);
        } catch (ApiException ex) {
            if (ex.getErrorCode() == ErrorCode.CHECKSUM_MISMATCH) {
                handleChecksumMismatchFailure(entity);
            }
            throw ex;
        }

        // Post-assembly: CompleteMultipartUpload has succeeded, so we can no longer roll back to PENDING.
        VerifiedObject verified;
        try {
            verified = objectIntegrityVerifier.verifyCommittedObject(entity, expectedChecksum);
        } catch (ApiException ex) {
            if (ex.getErrorCode() != ErrorCode.STORAGE_UNAVAILABLE) {
                failCommitWithCleanup(entity, CleanupKind.DELETE_OBJECT);
            }
            throw ex;
        }

        Instant committedAt = Instant.now();
        boolean committed = Boolean.TRUE.equals(transactionTemplate.execute(status ->
                storageObjectRepository.markCommittedWithActualSize(
                        uploadId,
                        verified.sizeBytes(),
                        verified.etag(),
                        verified.checksum(),
                        verified.checksumAlgorithm(),
                        committedAt) == 1));

        if (!committed) {
            throw ApiException.conflict("Upload state changed concurrently during commit");
        }

        eventPublisher.mediaUploaded(new MediaUploadedEvent(
                entity.getMediaId(),
                entity.getGroupId(),
                entity.getLibraryId(),
                entity.getUserId(),
                entity.getBucket(),
                entity.getObjectKey(),
                entity.getContentType(),
                verified.sizeBytes(),
                verified.checksum()));

        log.info(
                "Committed uploadId={} mediaId={} verifiedSizeBytes={} checksumAlgorithm={}",
                uploadId,
                entity.getMediaId(),
                verified.sizeBytes(),
                verified.checksumAlgorithm());

        return new CompleteUploadResponse(
                uploadId,
                entity.getMediaId(),
                StorageObjectStatus.COMMITTED.toOpenApiStatus(),
                verified.sizeBytes(),
                verified.checksum(),
                verified.etag());
    }

    private static Map<Integer, String> validateClientPartChecksums(
            List<PartChecksumDto> parts, int totalParts) {
        if (parts.size() != totalParts) {
            throw ApiException.of(
                    ErrorCode.VALIDATION_ERROR,
                    "parts must contain exactly " + totalParts + " entries");
        }
        Map<Integer, String> byPartNumber = new HashMap<>(totalParts);
        for (PartChecksumDto dto : parts) {
            if (dto.partNumber() == null || dto.partNumber() < 1 || dto.partNumber() > totalParts) {
                throw ApiException.of(
                        ErrorCode.VALIDATION_ERROR,
                        "Part number " + dto.partNumber() + " out of range 1.." + totalParts);
            }
            String normalizedCrc = Crc32cCombiner.requireValidBase64(
                    dto.crc32c(), "parts[" + dto.partNumber() + "].crc32c");
            if (byPartNumber.putIfAbsent(dto.partNumber(), normalizedCrc) != null) {
                throw ApiException.of(
                        ErrorCode.VALIDATION_ERROR,
                        "Duplicate partNumber in complete request: " + dto.partNumber());
            }
        }
        return byPartNumber;
    }

    private List<CompletedPartSpec> verifyUploadedParts(
            StorageObjectEntity entity,
            List<UploadedPart> s3Parts,
            Map<Integer, String> clientPartChecksums,
            String expectedChecksum) {
        Map<Integer, UploadedPart> byPartNumber = new HashMap<>(s3Parts.size());
        for (UploadedPart part : s3Parts) {
            if (byPartNumber.putIfAbsent(part.partNumber(), part) != null) {
                failCommitWithCleanup(entity, CleanupKind.ABORT_MP);
                throw ApiException.conflict("Duplicate part number reported by storage: " + part.partNumber());
            }
        }

        int totalParts = entity.getTotalParts();
        List<Integer> missingParts = new ArrayList<>();
        for (int partNumber = 1; partNumber <= totalParts; partNumber++) {
            if (!byPartNumber.containsKey(partNumber)) {
                missingParts.add(partNumber);
            }
        }
        if (!missingParts.isEmpty()) {
            rollbackToPending(entity.getUploadId());
            throw ApiException.partsMissing(
                    "Missing uploaded parts " + missingParts + " of " + totalParts,
                    missingParts);
        }

        if (byPartNumber.size() != totalParts) {
            failCommitWithCleanup(entity, CleanupKind.ABORT_MP);
            throw ApiException.sizeMismatch(entity.getDeclaredSize(), -1L);
        }

        List<Integer> badParts = new ArrayList<>();
        List<CompletedPartSpec> specs = new ArrayList<>(totalParts);
        List<PartChecksum> partChecksums = new ArrayList<>(totalParts);

        for (int partNumber = 1; partNumber <= totalParts; partNumber++) {
            UploadedPart s3Part = byPartNumber.get(partNumber);
            long expectedPartSize = PartGeometry.expectedPartSize(
                    entity.getDeclaredSize(), entity.getPartSize(), totalParts, partNumber);
            String clientCrc = clientPartChecksums.get(partNumber);

            boolean missingStorageChecksum = s3Properties.fullObjectChecksumEnabled()
                    && (s3Part.checksumCrc32c() == null || s3Part.checksumCrc32c().isBlank());
            boolean storageChecksumMismatch = s3Part.checksumCrc32c() != null
                    && !s3Part.checksumCrc32c().isBlank()
                    && !clientCrc.equals(s3Part.checksumCrc32c());

            if (s3Part.sizeBytes() != expectedPartSize
                    || missingStorageChecksum
                    || storageChecksumMismatch) {
                badParts.add(partNumber);
            } else {
                partChecksums.add(new PartChecksum(clientCrc, s3Part.sizeBytes()));
                String specCrc = s3Part.checksumCrc32c() != null && !s3Part.checksumCrc32c().isBlank()
                        ? s3Part.checksumCrc32c()
                        : clientCrc;
                specs.add(new CompletedPartSpec(partNumber, s3Part.etag(), specCrc));
            }
        }

        if (!badParts.isEmpty()) {
            handleChecksumMismatchFailure(entity);
            throw ApiException.checksumMismatch(
                    "Part checksum or size mismatch for parts " + badParts,
                    badParts);
        }

        String computedComposite = Crc32cCombiner.combineBase64(partChecksums);
        if (!expectedChecksum.equals(computedComposite)) {
            rollbackToPending(entity.getUploadId());
            throw ApiException.compositeChecksumInvalid(
                    "Combined part CRC32C (" + computedComposite
                            + ") does not match expectedChecksum (" + expectedChecksum
                            + "); re-uploading parts will not fix a composite calculation mismatch");
        }

        return specs;
    }

    private void rollbackToPending(UUID uploadId) {
        transactionTemplate.executeWithoutResult(
                status -> storageObjectRepository.rollbackCommitToPending(uploadId, Instant.now()));
    }

    private void handleChecksumMismatchFailure(StorageObjectEntity entity) {
        Instant now = Instant.now();
        if (entity.getChecksumMismatchCount() + 1 >= MAX_CHECKSUM_MISMATCH_RETRIES) {
            transactionTemplate.executeWithoutResult(status -> {
                int updated = storageObjectRepository.incrementMismatchAndFail(entity.getUploadId(), now);
                if (updated == 1) {
                    storageCleanupRepository.save(StorageCleanupEntity.builder()
                            .kind(CleanupKind.ABORT_MP)
                            .bucket(entity.getBucket())
                            .objectKey(entity.getObjectKey())
                            .providerUploadId(entity.getProviderUploadId())
                            .attempts(0)
                            .runAfter(now)
                            .build());
                }
            });
        } else {
            transactionTemplate.executeWithoutResult(
                    status -> storageObjectRepository.incrementMismatchAndRollback(entity.getUploadId(), now));
        }
    }

    private void failCommitWithCleanup(StorageObjectEntity entity, CleanupKind cleanupKind) {
        Instant now = Instant.now();
        transactionTemplate.executeWithoutResult(status -> {
            int updated = storageObjectRepository.transitionStatus(
                    entity.getUploadId(),
                    List.of(StorageObjectStatus.COMMITTING),
                    StorageObjectStatus.FAILED,
                    now);
            if (updated == 1) {
                storageCleanupRepository.save(StorageCleanupEntity.builder()
                        .kind(cleanupKind)
                        .bucket(entity.getBucket())
                        .objectKey(entity.getObjectKey())
                        .providerUploadId(cleanupKind == CleanupKind.ABORT_MP ? entity.getProviderUploadId() : null)
                        .attempts(0)
                        .runAfter(now)
                        .build());
            }
        });
    }
}
