package com.mediamesh.ingestion.storage;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.config.S3Properties;
import com.mediamesh.ingestion.storage.persistence.StorageObjectEntity;
import com.mediamesh.ingestion.storage.persistence.StorageObjectRepository;
import com.mediamesh.ingestion.storage.persistence.StorageObjectStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared helpers reused across upload handlers.
 */
public final class UploadSupport {

    private UploadSupport() {
    }

    /**
     * Loads an upload session by ID and ensures the requesting user owns it.
     */
    public static StorageObjectEntity requireOwnedUpload(
            StorageObjectRepository storageObjectRepository, UUID uploadId, UUID userId) {
        StorageObjectEntity entity = storageObjectRepository.findById(uploadId)
                .orElseThrow(() -> ApiException.uploadNotFound(uploadId));
        requireOwner(entity, userId);
        return entity;
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
     * Validates that the upload session is still {@code PENDING} and not expired.
     */
    public static void requireUploadAcceptingParts(StorageObjectEntity entity, Instant now) {
        if (entity.getStatus() == StorageObjectStatus.EXPIRED
                || entity.getStatus() == StorageObjectStatus.ABORTED
                || (entity.getStatus() == StorageObjectStatus.PENDING && !entity.getExpiresAt().isAfter(now))) {
            throw ApiException.gone("Upload session is no longer active");
        }
        if (entity.getStatus() != StorageObjectStatus.PENDING) {
            throw ApiException.conflict("Upload is not accepting parts in state: " + entity.getStatus());
        }
    }

    /**
     * Slides {@code expires_at} forward by {@code uploadTtl}, capped at {@code createdAt + maxUploadLifetime}.
     */
    public static void slideUploadExpiration(
            StorageObjectRepository storageObjectRepository,
            TransactionTemplate transactionTemplate,
            StorageObjectEntity entity,
            S3Properties s3Properties,
            Instant now) {
        Instant maxExpiresAt = entity.getCreatedAt().plus(s3Properties.maxUploadLifetime());
        Instant candidateExpiresAt = now.plus(s3Properties.uploadTtl());
        Instant newExpiresAt = candidateExpiresAt.isAfter(maxExpiresAt) ? maxExpiresAt : candidateExpiresAt;
        transactionTemplate.executeWithoutResult(
                status -> storageObjectRepository.slideExpiresAt(entity.getUploadId(), newExpiresAt, now));
    }
}
