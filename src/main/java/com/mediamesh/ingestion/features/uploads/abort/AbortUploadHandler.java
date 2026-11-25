package com.mediamesh.ingestion.features.uploads.abort;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.storage.UploadSupport;
import com.mediamesh.ingestion.storage.persistence.CleanupKind;
import com.mediamesh.ingestion.storage.persistence.StorageCleanupEntity;
import com.mediamesh.ingestion.storage.persistence.StorageCleanupRepository;
import com.mediamesh.ingestion.storage.persistence.StorageObjectEntity;
import com.mediamesh.ingestion.storage.persistence.StorageObjectRepository;
import com.mediamesh.ingestion.storage.persistence.StorageObjectStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles idempotent upload abortion and deferred multipart cleanup scheduling.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AbortUploadHandler {

    private final StorageObjectRepository storageObjectRepository;
    private final StorageCleanupRepository storageCleanupRepository;
    private final TransactionTemplate transactionTemplate;

    public AbortUploadResponse handle(UUID uploadId, UUID userId) {
        StorageObjectEntity entity = storageObjectRepository.findById(uploadId)
                .orElseThrow(() -> ApiException.uploadNotFound(uploadId));
        UploadSupport.requireOwner(entity, userId);

        if (entity.getStatus() == StorageObjectStatus.ABORTED) {
            return new AbortUploadResponse(uploadId, StorageObjectStatus.ABORTED.toOpenApiStatus());
        }
        if (entity.getStatus() == StorageObjectStatus.EXPIRED) {
            throw ApiException.gone("Upload has already expired");
        }
        if (entity.getStatus() == StorageObjectStatus.COMMITTING
                || entity.getStatus() == StorageObjectStatus.COMMITTED
                || entity.getStatus() == StorageObjectStatus.DELETED) {
            throw ApiException.conflict("Upload cannot be aborted in state: " + entity.getStatus());
        }

        Instant now = Instant.now();
        boolean transitioned = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            int updated = storageObjectRepository.transitionStatus(
                    uploadId,
                    List.of(StorageObjectStatus.ALLOCATING, StorageObjectStatus.PENDING),
                    StorageObjectStatus.ABORTED,
                    now);
            if (updated == 1) {
                storageCleanupRepository.save(StorageCleanupEntity.builder()
                        .kind(CleanupKind.ABORT_MP)
                        .bucket(entity.getBucket())
                        .objectKey(entity.getObjectKey())
                        .providerUploadId(entity.getProviderUploadId())
                        .attempts(0)
                        .runAfter(now)
                        .build());
                return true;
            }
            return false;
        }));

        if (!transitioned) {
            StorageObjectEntity current = storageObjectRepository.findById(uploadId)
                    .orElseThrow(() -> ApiException.uploadNotFound(uploadId));
            if (current.getStatus() == StorageObjectStatus.ABORTED) {
                return new AbortUploadResponse(uploadId, StorageObjectStatus.ABORTED.toOpenApiStatus());
            }
            if (current.getStatus() == StorageObjectStatus.EXPIRED) {
                throw ApiException.gone("Upload has already expired");
            }
            throw ApiException.conflict("Upload cannot be aborted in state: " + current.getStatus());
        }

        log.info("Aborted uploadId={} mediaId={}", uploadId, entity.getMediaId());
        return new AbortUploadResponse(uploadId, StorageObjectStatus.ABORTED.toOpenApiStatus());
    }
}
