package com.mediamesh.ingestion.features.uploads.complete;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import com.mediamesh.ingestion.config.S3Properties;
import com.mediamesh.ingestion.features.uploads.complete.CompleteUploadRequest.PartChecksumDto;
import com.mediamesh.ingestion.storage.events.EventPublisher;
import com.mediamesh.ingestion.storage.persistence.CleanupKind;
import com.mediamesh.ingestion.storage.persistence.StorageCleanupEntity;
import com.mediamesh.ingestion.storage.persistence.StorageCleanupRepository;
import com.mediamesh.ingestion.storage.persistence.StorageObjectEntity;
import com.mediamesh.ingestion.storage.persistence.StorageObjectRepository;
import com.mediamesh.ingestion.storage.persistence.StorageObjectStatus;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.UploadedPart;
import com.mediamesh.ingestion.storage.verification.Crc32cCombiner;
import com.mediamesh.ingestion.storage.verification.ObjectIntegrityVerifier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class CompleteUploadHandlerTest {

    private static final long MIB = 1024L * 1024L;

    private final StorageObjectRepository objectRepo = mock(StorageObjectRepository.class);
    private final StorageCleanupRepository cleanupRepo = mock(StorageCleanupRepository.class);
    private final ObjectStorageProvider storageProvider = mock(ObjectStorageProvider.class);
    private final ObjectIntegrityVerifier integrityVerifier = mock(ObjectIntegrityVerifier.class);
    private final EventPublisher eventPublisher = mock(EventPublisher.class);
    private final S3Properties s3Properties = new S3Properties(
            "http://localhost:9000", null, "us-east-1", "a", "s",
            "mediamesh-raw", "mediamesh-processed", true, true,
            Duration.ofMinutes(20), Duration.ofHours(24), Duration.ofDays(7),
            5_497_558_138_880L, 65_536);
    private final TransactionTemplate txTemplate = new TransactionTemplate(new PlatformTransactionManager() {
        @Override
        public org.springframework.transaction.TransactionStatus getTransaction(
                org.springframework.transaction.TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(org.springframework.transaction.TransactionStatus status) {}

        @Override
        public void rollback(org.springframework.transaction.TransactionStatus status) {}
    });

    private final CompleteUploadHandler handler = new CompleteUploadHandler(
            objectRepo, cleanupRepo, storageProvider, integrityVerifier, s3Properties, txTemplate, eventPublisher);

    @Test
    void partCrcMismatchRollsBackOnFirstTwoAndAbortsOnThird() {
        UUID uploadId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String s3Crc = Crc32cCombiner.computeBase64(new byte[] {1, 2, 3});
        String wrongClientCrc = Crc32cCombiner.computeBase64(new byte[] {9, 9, 9});

        StorageObjectEntity entity = sampleEntity(uploadId, userId, 0);
        when(objectRepo.findById(uploadId)).thenReturn(Optional.of(entity));
        when(objectRepo.claimForCommit(eq(uploadId), eq(wrongClientCrc), any())).thenReturn(1);
        when(storageProvider.listParts("mediamesh-raw", entity.getObjectKey(), "mp-1"))
                .thenReturn(List.of(new UploadedPart(1, 8 * MIB, "e1", s3Crc)));

        CompleteUploadRequest req = new CompleteUploadRequest(
                wrongClientCrc, List.of(new PartChecksumDto(1, wrongClientCrc)));

        // Attempt 1 (count=0 -> 1): rolls back, does not fail/abort
        assertThatThrownBy(() -> handler.handle(uploadId, userId, req))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHECKSUM_MISMATCH);
                    assertThat(ex.getProperties()).containsEntry("badParts", List.of(1));
                });
        verify(objectRepo).incrementMismatchAndRollback(eq(uploadId), any());
        verify(cleanupRepo, never()).save(any());

        // Attempt 3 (count=2 -> 3): fails and enqueues ABORT_MP
        entity.setChecksumMismatchCount(2);
        when(objectRepo.incrementMismatchAndFail(eq(uploadId), any())).thenReturn(1);

        assertThatThrownBy(() -> handler.handle(uploadId, userId, req))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHECKSUM_MISMATCH);

        verify(objectRepo).incrementMismatchAndFail(eq(uploadId), any());
        ArgumentCaptor<StorageCleanupEntity> captor = ArgumentCaptor.forClass(StorageCleanupEntity.class);
        verify(cleanupRepo).save(captor.capture());
        assertThat(captor.getValue().getKind()).isEqualTo(CleanupKind.ABORT_MP);
        assertThat(captor.getValue().getProviderUploadId()).isEqualTo("mp-1");
    }

    @Test
    void postAssemblyFailureDeletesObjectAndCannotRollBack() {
        UUID uploadId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String crc = Crc32cCombiner.computeBase64(new byte[] {1, 2, 3});

        StorageObjectEntity entity = sampleEntity(uploadId, userId, 0);
        when(objectRepo.findById(uploadId)).thenReturn(Optional.of(entity));
        when(objectRepo.claimForCommit(eq(uploadId), eq(crc), any())).thenReturn(1);
        when(storageProvider.listParts("mediamesh-raw", entity.getObjectKey(), "mp-1"))
                .thenReturn(List.of(new UploadedPart(1, 8 * MIB, "e1", crc)));
        when(integrityVerifier.verifyCommittedObject(entity, crc))
                .thenThrow(ApiException.checksumMismatch("post-assembly mismatch"));
        when(objectRepo.transitionStatus(
                eq(uploadId),
                eq(List.of(StorageObjectStatus.COMMITTING)),
                eq(StorageObjectStatus.FAILED),
                any())).thenReturn(1);

        CompleteUploadRequest req = new CompleteUploadRequest(crc, List.of(new PartChecksumDto(1, crc)));

        assertThatThrownBy(() -> handler.handle(uploadId, userId, req))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHECKSUM_MISMATCH);

        verify(objectRepo, never()).rollbackCommitToPending(any(), any());
        ArgumentCaptor<StorageCleanupEntity> captor = ArgumentCaptor.forClass(StorageCleanupEntity.class);
        verify(cleanupRepo).save(captor.capture());
        assertThat(captor.getValue().getKind()).isEqualTo(CleanupKind.DELETE_OBJECT);
    }

    private static StorageObjectEntity sampleEntity(UUID uploadId, UUID userId, int mismatchCount) {
        return StorageObjectEntity.builder()
                .uploadId(uploadId)
                .mediaId(UUID.randomUUID())
                .groupId(UUID.randomUUID())
                .libraryId(UUID.randomUUID())
                .userId(userId)
                .fileName("test.mp4")
                .bucket("mediamesh-raw")
                .objectKey("raw/g/" + UUID.randomUUID())
                .providerUploadId("mp-1")
                .status(StorageObjectStatus.PENDING)
                .declaredSize(8 * MIB)
                .partSize(8 * MIB)
                .totalParts(1)
                .contentType("video/mp4")
                .checksumMismatchCount(mismatchCount)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
