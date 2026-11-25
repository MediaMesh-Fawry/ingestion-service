package com.mediamesh.ingestion.features.media.delete;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.config.S3Properties;
import com.mediamesh.ingestion.storage.PartGeometry;
import com.mediamesh.ingestion.storage.events.EventPublisher;
import com.mediamesh.ingestion.storage.events.EventPublisher.MediaDeletedEvent;
import com.mediamesh.ingestion.storage.persistence.CleanupKind;
import com.mediamesh.ingestion.storage.persistence.StorageCleanupEntity;
import com.mediamesh.ingestion.storage.persistence.StorageCleanupRepository;
import com.mediamesh.ingestion.storage.persistence.StorageObjectEntity;
import com.mediamesh.ingestion.storage.persistence.StorageObjectRepository;
import com.mediamesh.ingestion.storage.persistence.StorageObjectStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles idempotent soft deletion of committed media and enqueues raw + processed S3 cleanup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeleteMediaHandler {

    private final StorageObjectRepository storageObjectRepository;
    private final StorageCleanupRepository storageCleanupRepository;
    private final S3Properties s3Properties;
    private final TransactionTemplate transactionTemplate;
    private final EventPublisher eventPublisher;

    public void handle(UUID mediaId) {
        StorageObjectEntity entity = storageObjectRepository.findByMediaId(mediaId)
                .orElseThrow(() -> ApiException.mediaNotFound(mediaId));

        if (entity.getStatus() == StorageObjectStatus.DELETED) {
            return;
        }
        if (entity.getStatus() != StorageObjectStatus.COMMITTED) {
            throw ApiException.conflict("Media cannot be deleted in state: " + entity.getStatus());
        }

        Instant now = Instant.now();
        boolean deleted = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            int updated = storageObjectRepository.markDeletedByMediaId(mediaId, now, now);
            if (updated == 1) {
                storageCleanupRepository.save(StorageCleanupEntity.builder()
                        .kind(CleanupKind.DELETE_OBJECT)
                        .bucket(entity.getBucket())
                        .objectKey(entity.getObjectKey())
                        .attempts(0)
                        .runAfter(now)
                        .build());
                storageCleanupRepository.save(StorageCleanupEntity.builder()
                        .kind(CleanupKind.DELETE_PREFIX)
                        .bucket(s3Properties.processedBucket())
                        .objectKey(PartGeometry.processedPrefix(entity.getGroupId(), entity.getMediaId()))
                        .attempts(0)
                        .runAfter(now)
                        .build());
                return true;
            }
            return false;
        }));

        if (!deleted) {
            StorageObjectEntity current = storageObjectRepository.findByMediaId(mediaId)
                    .orElseThrow(() -> ApiException.mediaNotFound(mediaId));
            if (current.getStatus() == StorageObjectStatus.DELETED) {
                return;
            }
            throw ApiException.conflict("Media cannot be deleted in state: " + current.getStatus());
        }

        eventPublisher.mediaDeleted(new MediaDeletedEvent(entity.getMediaId(), entity.getGroupId(), now));
        log.info("Soft-deleted mediaId={} uploadId={}", mediaId, entity.getUploadId());
    }
}
