package com.mediamesh.ingestion.storage.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mediamesh.ingestion.TestcontainersConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StoragePersistenceTest {

    @Autowired
    private StorageObjectRepository storageObjectRepository;

    @Autowired
    private StorageCleanupRepository storageCleanupRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void guardedStateTransitionsAndSoftDeleteEnforceStateMachine() {
        UUID uploadId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        Instant initialExpiry = Instant.now().plus(1, ChronoUnit.HOURS);

        StorageObjectEntity created = StorageObjectEntity.builder()
                .uploadId(uploadId)
                .mediaId(mediaId)
                .groupId(UUID.randomUUID())
                .libraryId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .fileName("clip.mp4")
                .title("Test Clip")
                .bucket("mediamesh-raw")
                .objectKey("raw/test/" + mediaId)
                .status(StorageObjectStatus.ALLOCATING)
                .declaredSize(12_582_912L)
                .partSize(8_388_608L)
                .totalParts(2)
                .contentType("video/mp4")
                .expiresAt(initialExpiry)
                .build();
        storageObjectRepository.save(created);

        Instant now = Instant.now();
        int pendingRows = transactionTemplate.execute(
                status -> storageObjectRepository.markPendingWithProviderUploadId(uploadId, "mp-123", now));
        assertThat(pendingRows).isEqualTo(1);

        int duplicatePendingRows = transactionTemplate.execute(
                status -> storageObjectRepository.markPendingWithProviderUploadId(uploadId, "mp-999", now));
        assertThat(duplicatePendingRows).isZero();

        Instant extendedExpiry = initialExpiry.plus(2, ChronoUnit.HOURS);
        int slid = transactionTemplate.execute(
                status -> storageObjectRepository.slideExpiresAt(uploadId, extendedExpiry, now));
        assertThat(slid).isEqualTo(1);

        int backwardsSlide = transactionTemplate.execute(
                status -> storageObjectRepository.slideExpiresAt(uploadId, initialExpiry, now));
        assertThat(backwardsSlide).isZero();

        int committingRows = transactionTemplate.execute(status -> storageObjectRepository.transitionStatus(
                uploadId, List.of(StorageObjectStatus.PENDING), StorageObjectStatus.COMMITTING, now));
        assertThat(committingRows).isEqualTo(1);

        int concurrentCommitting = transactionTemplate.execute(status -> storageObjectRepository.transitionStatus(
                uploadId, List.of(StorageObjectStatus.PENDING), StorageObjectStatus.COMMITTING, now));
        assertThat(concurrentCommitting).isZero();

        int committedRows = transactionTemplate.execute(status -> storageObjectRepository.markCommittedWithActualSize(
                uploadId, 12_582_912L, "etag-abc-2", "crc32c-val", "CRC32C", now));
        assertThat(committedRows).isEqualTo(1);

        Instant deletedAt = Instant.now();
        int deletedRows = transactionTemplate.execute(
                status -> storageObjectRepository.markDeletedByMediaId(mediaId, deletedAt, deletedAt));
        assertThat(deletedRows).isEqualTo(1);

        int secondDelete = transactionTemplate.execute(
                status -> storageObjectRepository.markDeletedByMediaId(mediaId, deletedAt, deletedAt));
        assertThat(secondDelete).isZero();

        StorageObjectEntity finalRow = storageObjectRepository.findByMediaId(mediaId).orElseThrow();
        assertThat(finalRow.getStatus()).isEqualTo(StorageObjectStatus.DELETED);
        assertThat(finalRow.getProviderUploadId()).isEqualTo("mp-123");
        assertThat(finalRow.getActualSize()).isEqualTo(12_582_912L);
        assertThat(finalRow.getEtag()).isEqualTo("etag-abc-2");
        assertThat(finalRow.getChecksum()).isEqualTo("crc32c-val");
        assertThat(finalRow.getChecksumAlgorithm()).isEqualTo("CRC32C");
        assertThat(finalRow.getDeletedAt()).isNotNull();
    }

    @Test
    void sweepQueriesReturnExpiredPendingAndStuckCommitting() {
        Instant now = Instant.now();
        UUID expiredUploadId = UUID.randomUUID();
        UUID stuckUploadId = UUID.randomUUID();

        storageObjectRepository.save(StorageObjectEntity.builder()
                .uploadId(expiredUploadId)
                .mediaId(UUID.randomUUID())
                .groupId(UUID.randomUUID())
                .libraryId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .fileName("expired.mp4")
                .bucket("mediamesh-raw")
                .objectKey("raw/test/" + expiredUploadId)
                .status(StorageObjectStatus.PENDING)
                .declaredSize(1024L)
                .partSize(8_388_608L)
                .totalParts(1)
                .contentType("video/mp4")
                .expiresAt(now.minus(5, ChronoUnit.MINUTES))
                .build());

        storageObjectRepository.save(StorageObjectEntity.builder()
                .uploadId(stuckUploadId)
                .mediaId(UUID.randomUUID())
                .groupId(UUID.randomUUID())
                .libraryId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .fileName("stuck.mp4")
                .bucket("mediamesh-raw")
                .objectKey("raw/test/" + stuckUploadId)
                .status(StorageObjectStatus.COMMITTING)
                .declaredSize(2048L)
                .partSize(8_388_608L)
                .totalParts(1)
                .contentType("video/mp4")
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .build());

        List<StorageObjectEntity> expired =
                storageObjectRepository.findExpiredPending(now, PageRequest.of(0, 10));
        assertThat(expired).extracting(StorageObjectEntity::getUploadId).contains(expiredUploadId);

        List<StorageObjectEntity> stuck = storageObjectRepository.findStuckCommitting(
                now.plus(1, ChronoUnit.MINUTES), PageRequest.of(0, 10));
        assertThat(stuck).extracting(StorageObjectEntity::getUploadId).contains(stuckUploadId);
    }

    @Test
    void cleanupRepositoryHandlesPlaceholdersClaimAndBackoff() {
        String key = "raw/test/cleanup-" + UUID.randomUUID();
        Instant past = Instant.now().minus(1, ChronoUnit.MINUTES);

        StorageCleanupEntity placeholder = storageCleanupRepository.save(StorageCleanupEntity.builder()
                .kind(CleanupKind.ABORT_MP)
                .bucket("mediamesh-raw")
                .objectKey(key)
                .providerUploadId(null)
                .attempts(0)
                .runAfter(past)
                .build());

        StorageCleanupEntity withUploadId = storageCleanupRepository.save(StorageCleanupEntity.builder()
                .kind(CleanupKind.ABORT_MP)
                .bucket("mediamesh-raw")
                .objectKey(key)
                .providerUploadId("mp-keep")
                .attempts(0)
                .runAfter(past)
                .build());

        int deletedPlaceholders = transactionTemplate.execute(
                status -> storageCleanupRepository.deletePlaceholderAbort("mediamesh-raw", key));
        assertThat(deletedPlaceholders).isEqualTo(1);
        assertThat(storageCleanupRepository.findById(placeholder.getId())).isEmpty();
        assertThat(storageCleanupRepository.findById(withUploadId.getId())).isPresent();

        List<StorageCleanupEntity> claimed = transactionTemplate.execute(
                status -> storageCleanupRepository.claimDueTasks(Instant.now(), 10));
        assertThat(claimed)
                .extracting(StorageCleanupEntity::getId)
                .contains(withUploadId.getId());

        Instant nextRun = Instant.now().plus(10, ChronoUnit.MINUTES);
        int rescheduled = transactionTemplate.execute(
                status -> storageCleanupRepository.rescheduleWithBackoff(withUploadId.getId(), 1, nextRun));
        assertThat(rescheduled).isEqualTo(1);

        StorageCleanupEntity reloaded =
                storageCleanupRepository.findById(withUploadId.getId()).orElseThrow();
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getRunAfter()).isAfter(Instant.now().plus(5, ChronoUnit.MINUTES));
    }
}
