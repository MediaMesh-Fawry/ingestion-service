package com.mediamesh.ingestion.features.uploads.complete;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import com.mediamesh.ingestion.features.uploads.complete.CompleteUploadRequest.SubmittedPartDto;
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

    private final StorageObjectRepository storageObjectRepository;
    private final StorageCleanupRepository storageCleanupRepository;
    private final ObjectStorageProvider objectStorageProvider;
    private final ObjectIntegrityVerifier objectIntegrityVerifier;
    private final TransactionTemplate transactionTemplate;
    private final EventPublisher eventPublisher;

    public CompleteUploadResponse handle(UUID uploadId, UUID userId) {
        return handle(uploadId, userId, null);
    }

    public CompleteUploadResponse handle(UUID uploadId, UUID userId, CompleteUploadRequest request) {
        StorageObjectEntity entity = storageObjectRepository.findById(uploadId)
                .orElseThrow(() -> ApiException.uploadNotFound(uploadId));
        UploadSupport.requireOwner(entity, userId);

        String requestExpectedChecksum = request != null
                ? Crc32cCombiner.normalizeAndValidateBase64(request.expectedChecksum())
                : null;
        String effectiveExpectedChecksum = resolveExpectedChecksum(
                entity.getExpectedChecksum(), requestExpectedChecksum);

        Instant now = Instant.now();
        boolean claimed = Boolean.TRUE.equals(transactionTemplate.execute(status ->
                storageObjectRepository.claimForCommit(uploadId, now) == 1));

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

        VerifiedParts verifiedParts = verifyUploadedParts(entity, s3Parts, request, effectiveExpectedChecksum);

        try {
            objectStorageProvider.completeMultipart(
                    entity.getBucket(),
                    entity.getObjectKey(),
                    entity.getProviderUploadId(),
                    verifiedParts.specs(),
                    effectiveExpectedChecksum);
        } catch (ApiException ex) {
            if (ex.getErrorCode() == ErrorCode.CHECKSUM_MISMATCH) {
                failCommitWithCleanup(entity, CleanupKind.ABORT_MP);
            }
            throw ex;
        }

        if (effectiveExpectedChecksum != null) {
            entity.setExpectedChecksum(effectiveExpectedChecksum);
        }

        VerifiedObject verified;
        try {
            verified = objectIntegrityVerifier.verifyCommittedObject(
                    entity, verifiedParts.computedCompositeCrc32c());
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

    private VerifiedParts verifyUploadedParts(
            StorageObjectEntity entity,
            List<UploadedPart> s3Parts,
            CompleteUploadRequest request,
            String effectiveExpectedChecksum) {
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
                    "Missing uploaded parts " + missingParts + " of " + totalParts);
        }

        if (byPartNumber.size() != totalParts) {
            failCommitWithCleanup(entity, CleanupKind.ABORT_MP);
            throw ApiException.sizeMismatch(entity.getDeclaredSize(), -1L);
        }

        Map<Integer, String> clientEtags = extractClientEtags(request);
        long sumBytes = 0L;
        List<CompletedPartSpec> specs = new ArrayList<>(totalParts);
        List<PartChecksum> partChecksums = new ArrayList<>(totalParts);
        boolean allPartsHaveCrc32c = true;

        for (int partNumber = 1; partNumber <= totalParts; partNumber++) {
            UploadedPart part = byPartNumber.get(partNumber);
            long expectedPartSize = PartGeometry.expectedPartSize(
                    entity.getDeclaredSize(), entity.getPartSize(), totalParts, partNumber);
            if (part.sizeBytes() != expectedPartSize) {
                failCommitWithCleanup(entity, CleanupKind.ABORT_MP);
                throw ApiException.sizeMismatch(expectedPartSize, part.sizeBytes());
            }

            String clientEtag = clientEtags.get(partNumber);
            if (clientEtag != null && !clientEtag.equals(stripQuotes(part.etag()))) {
                failCommitWithCleanup(entity, CleanupKind.ABORT_MP);
                throw ApiException.checksumMismatch(
                        "Part " + partNumber + " ETag mismatch against object storage");
            }

            if (part.checksumCrc32c() != null && !part.checksumCrc32c().isBlank()) {
                partChecksums.add(new PartChecksum(part.checksumCrc32c(), part.sizeBytes()));
            } else {
                allPartsHaveCrc32c = false;
            }

            sumBytes += part.sizeBytes();
            specs.add(new CompletedPartSpec(partNumber, part.etag(), part.checksumCrc32c()));
        }

        if (sumBytes != entity.getDeclaredSize()) {
            failCommitWithCleanup(entity, CleanupKind.ABORT_MP);
            throw ApiException.sizeMismatch(entity.getDeclaredSize(), sumBytes);
        }

        String computedCompositeCrc32c = allPartsHaveCrc32c
                ? Crc32cCombiner.combineBase64(partChecksums)
                : null;

        if (effectiveExpectedChecksum != null
                && computedCompositeCrc32c != null
                && !effectiveExpectedChecksum.equals(computedCompositeCrc32c)) {
            failCommitWithCleanup(entity, CleanupKind.ABORT_MP);
            throw ApiException.checksumMismatch(
                    "Combined part CRC32C (" + computedCompositeCrc32c
                            + ") does not match expected full-object CRC32C (" + effectiveExpectedChecksum + ")");
        }

        return new VerifiedParts(specs, computedCompositeCrc32c);
    }

    private static String resolveExpectedChecksum(String initChecksum, String completeChecksum) {
        if (initChecksum != null && completeChecksum != null && !initChecksum.equals(completeChecksum)) {
            throw ApiException.checksumMismatch(
                    "Completion expectedChecksum does not match checksum declared at upload init");
        }
        return initChecksum != null ? initChecksum : completeChecksum;
    }

    private static Map<Integer, String> extractClientEtags(CompleteUploadRequest request) {
        if (request == null || request.parts() == null || request.parts().isEmpty()) {
            return Map.of();
        }
        Map<Integer, String> map = new HashMap<>(request.parts().size());
        for (SubmittedPartDto dto : request.parts()) {
            if (dto.etag() != null && !dto.etag().isBlank()) {
                map.put(dto.partNumber(), stripQuotes(dto.etag().trim()));
            }
        }
        return map;
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private void rollbackToPending(UUID uploadId) {
        transactionTemplate.executeWithoutResult(
                status -> storageObjectRepository.rollbackCommitToPending(uploadId, Instant.now()));
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

    private record VerifiedParts(List<CompletedPartSpec> specs, String computedCompositeCrc32c) {}
}
