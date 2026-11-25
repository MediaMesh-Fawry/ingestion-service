package com.mediamesh.ingestion.features.cleanup;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import com.mediamesh.ingestion.storage.events.EventPublisher;
import com.mediamesh.ingestion.storage.events.EventPublisher.MediaUploadedEvent;
import com.mediamesh.ingestion.storage.persistence.CleanupKind;
import com.mediamesh.ingestion.storage.persistence.StorageCleanupEntity;
import com.mediamesh.ingestion.storage.persistence.StorageCleanupRepository;
import com.mediamesh.ingestion.storage.persistence.StorageObjectEntity;
import com.mediamesh.ingestion.storage.persistence.StorageObjectRepository;
import com.mediamesh.ingestion.storage.persistence.StorageObjectStatus;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.ObjectMetadata;
import com.mediamesh.ingestion.storage.verification.ObjectIntegrityVerifier;
import com.mediamesh.ingestion.storage.verification.ObjectIntegrityVerifier.VerifiedObject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Background reconciliation worker draining deferred S3 cleanup tasks,
 * expiring abandoned uploads, and recovering stuck {@code COMMITTING} rows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(CleanupProperties.class)
public class CleanupWorker {

    private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);

    private final StorageCleanupRepository storageCleanupRepository;
    private final StorageObjectRepository storageObjectRepository;
    private final ObjectStorageProvider objectStorageProvider;
    private final ObjectIntegrityVerifier objectIntegrityVerifier;
    private final EventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final CleanupProperties properties;

    @Scheduled(fixedDelayString = "${storage.cleanup.poll-interval:30s}")
    public void runAll() {
        drainCleanupQueue();
        expireStaleUploads();
        recoverStuckCommits();
    }

    /**
     * Claims due {@code storage_cleanup} rows with a lease, executes S3 cleanup outside DB transactions,
     * and deletes succeeded rows or reschedules failures with exponential backoff.
     */
    public int drainCleanupQueue() {
        Instant now = Instant.now();
        Instant leaseUntil = now.plus(properties.leaseDuration());

        List<StorageCleanupEntity> claimed = transactionTemplate.execute(
                status -> storageCleanupRepository.claimDueTasksWithLease(
                        now, leaseUntil, properties.batchSize()));

        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }

        int succeeded = 0;
        for (StorageCleanupEntity task : claimed) {
            try {
                executeCleanupTask(task);
                transactionTemplate.executeWithoutResult(
                        status -> storageCleanupRepository.deleteById(task.getId()));
                succeeded++;
            } catch (RuntimeException ex) {
                int nextAttempts = task.getAttempts() + 1;
                Duration backoff = computeBackoff(nextAttempts);
                Instant nextRunAfter = Instant.now().plus(backoff);
                log.warn(
                        "Cleanup task id={} kind={} bucket={} key={} failed (attempt={}), rescheduling for {}",
                        task.getId(),
                        task.getKind(),
                        task.getBucket(),
                        task.getObjectKey(),
                        nextAttempts,
                        nextRunAfter,
                        ex);
                transactionTemplate.executeWithoutResult(
                        status -> storageCleanupRepository.rescheduleWithBackoff(
                                task.getId(), nextAttempts, nextRunAfter));
            }
        }
        return succeeded;
    }

    /**
     * Transitions expired {@code PENDING} uploads to {@code EXPIRED} and enqueues {@code ABORT_MP}.
     */
    public int expireStaleUploads() {
        Instant now = Instant.now();
        List<StorageObjectEntity> expiredRows = storageObjectRepository.findExpiredPending(
                now, PageRequest.of(0, properties.batchSize()));

        int expiredCount = 0;
        for (StorageObjectEntity entity : expiredRows) {
            boolean updated = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                int count = storageObjectRepository.transitionStatus(
                        entity.getUploadId(),
                        List.of(StorageObjectStatus.PENDING),
                        StorageObjectStatus.EXPIRED,
                        now);
                if (count == 1) {
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
            if (updated) {
                expiredCount++;
                log.info("Expired stale uploadId={} mediaId={}", entity.getUploadId(), entity.getMediaId());
            }
        }
        return expiredCount;
    }

    /**
     * Reconciles {@code COMMITTING} rows that exceeded {@code stuckCommitAfter} against S3 {@code headObject}.
     */
    public int recoverStuckCommits() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(properties.stuckCommitAfter());
        List<StorageObjectEntity> stuckRows = storageObjectRepository.findStuckCommitting(
                cutoff, PageRequest.of(0, properties.batchSize()));

        int recovered = 0;
        for (StorageObjectEntity entity : stuckRows) {
            Optional<ObjectMetadata> metadata = objectStorageProvider.headObject(
                    entity.getBucket(), entity.getObjectKey());

            VerifiedObject verified = null;
            if (metadata.isPresent()) {
                try {
                    verified = objectIntegrityVerifier.verifyCommittedObject(entity, null);
                } catch (ApiException ex) {
                    if (ex.getErrorCode() == ErrorCode.STORAGE_UNAVAILABLE) {
                        log.warn(
                                "Skipping stuck commit recovery uploadId={} due to storage unavailability",
                                entity.getUploadId(),
                                ex);
                        continue;
                    }
                    log.warn(
                            "Stuck commit uploadId={} failed integrity verification ({})",
                            entity.getUploadId(),
                            ex.getErrorCode());
                }
            }

            if (verified != null) {
                VerifiedObject finalVerified = verified;
                boolean committed = Boolean.TRUE.equals(transactionTemplate.execute(status ->
                        storageObjectRepository.markCommittedWithActualSize(
                                entity.getUploadId(),
                                finalVerified.sizeBytes(),
                                finalVerified.etag(),
                                finalVerified.checksum(),
                                finalVerified.checksumAlgorithm(),
                                Instant.now()) == 1));
                if (committed) {
                    eventPublisher.mediaUploaded(new MediaUploadedEvent(
                            entity.getMediaId(),
                            entity.getGroupId(),
                            entity.getLibraryId(),
                            entity.getUserId(),
                            entity.getBucket(),
                            entity.getObjectKey(),
                            entity.getContentType(),
                            finalVerified.sizeBytes(),
                            finalVerified.checksum()));
                    recovered++;
                    log.info("Recovered stuck commit uploadId={} -> COMMITTED", entity.getUploadId());
                }
            } else {
                CleanupKind kind = metadata.isPresent() ? CleanupKind.DELETE_OBJECT : CleanupKind.ABORT_MP;
                String providerUploadId = metadata.isPresent() ? null : entity.getProviderUploadId();
                boolean failed = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                    int count = storageObjectRepository.transitionStatus(
                            entity.getUploadId(),
                            List.of(StorageObjectStatus.COMMITTING),
                            StorageObjectStatus.FAILED,
                            Instant.now());
                    if (count == 1) {
                        storageCleanupRepository.save(StorageCleanupEntity.builder()
                                .kind(kind)
                                .bucket(entity.getBucket())
                                .objectKey(entity.getObjectKey())
                                .providerUploadId(providerUploadId)
                                .attempts(0)
                                .runAfter(Instant.now())
                                .build());
                        return true;
                    }
                    return false;
                }));
                if (failed) {
                    recovered++;
                    log.warn(
                            "Recovered stuck commit uploadId={} -> FAILED (cleanup={})",
                            entity.getUploadId(),
                            kind);
                }
            }
        }
        return recovered;
    }

    private void executeCleanupTask(StorageCleanupEntity task) {
        switch (task.getKind()) {
            case ABORT_MP -> {
                if (task.getProviderUploadId() != null && !task.getProviderUploadId().isBlank()) {
                    objectStorageProvider.abortMultipart(
                            task.getBucket(), task.getObjectKey(), task.getProviderUploadId());
                } else {
                    List<String> uploadIds = objectStorageProvider.listMultipartUploads(
                            task.getBucket(), task.getObjectKey());
                    for (String uploadId : uploadIds) {
                        objectStorageProvider.abortMultipart(task.getBucket(), task.getObjectKey(), uploadId);
                    }
                }
            }
            case DELETE_OBJECT -> objectStorageProvider.deleteObject(task.getBucket(), task.getObjectKey());
            case DELETE_PREFIX -> objectStorageProvider.deletePrefix(task.getBucket(), task.getObjectKey());
        }
    }

    private Duration computeBackoff(int attempts) {
        long multiplier = 1L << Math.min(attempts - 1, 10);
        Duration candidate = BASE_BACKOFF.multipliedBy(multiplier);
        return candidate.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : candidate;
    }
}
