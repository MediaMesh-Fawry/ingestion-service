package com.mediamesh.ingestion.storage.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import com.mediamesh.ingestion.config.S3Properties;
import com.mediamesh.ingestion.storage.persistence.StorageObjectEntity;
import com.mediamesh.ingestion.storage.persistence.StorageObjectStatus;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.ObjectMetadata;
import com.mediamesh.ingestion.storage.verification.ObjectIntegrityVerifier.VerifiedObject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObjectIntegrityVerifierTest {

    private final ObjectStorageProvider storageProvider = mock(ObjectStorageProvider.class);
    private final S3Properties properties = new S3Properties(
            "http://localhost:9000",
            null,
            "us-east-1",
            "access",
            "secret",
            "mediamesh-raw",
            "mediamesh-processed",
            true,
            true,
            Duration.ofMinutes(20),
            Duration.ofHours(24),
            Duration.ofDays(7),
            5_497_558_138_880L,
            65_536);
    private final ObjectIntegrityVerifier verifier = new ObjectIntegrityVerifier(storageProvider, properties);

    @Test
    void verifyCommittedObject_validMp4_returnsVerifiedObject() {
        StorageObjectEntity entity = sampleEntity();
        when(storageProvider.headObject("mediamesh-raw", entity.getObjectKey()))
                .thenReturn(Optional.of(new ObjectMetadata(1024L, "etag-1", "crc-expected")));
        when(storageProvider.readByteRange(eq("mediamesh-raw"), eq(entity.getObjectKey()), eq(0L), anyLong()))
                .thenReturn(new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'});

        VerifiedObject verified = verifier.verifyCommittedObject(entity, "crc-expected", "crc-expected");
        assertThat(verified.sizeBytes()).isEqualTo(1024L);
        assertThat(verified.etag()).isEqualTo("etag-1");
        assertThat(verified.checksum()).isEqualTo("crc-expected");
        assertThat(verified.checksumAlgorithm()).isEqualTo("CRC32C");
    }

    @Test
    void verifyCommittedObject_missingObject_throwsStorageUnavailable() {
        StorageObjectEntity entity = sampleEntity();
        when(storageProvider.headObject("mediamesh-raw", entity.getObjectKey())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verifier.verifyCommittedObject(entity, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.STORAGE_UNAVAILABLE);
    }

    @Test
    void verifyCommittedObject_sizeMismatch_throwsSizeMismatch() {
        StorageObjectEntity entity = sampleEntity();
        when(storageProvider.headObject("mediamesh-raw", entity.getObjectKey()))
                .thenReturn(Optional.of(new ObjectMetadata(512L, "etag-1", null)));

        assertThatThrownBy(() -> verifier.verifyCommittedObject(entity, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SIZE_MISMATCH);
    }

    @Test
    void verifyCommittedObject_checksumMismatch_throwsChecksumMismatch() {
        StorageObjectEntity entity = sampleEntity();
        when(storageProvider.headObject("mediamesh-raw", entity.getObjectKey()))
                .thenReturn(Optional.of(new ObjectMetadata(1024L, "etag-1", "actual-crc")));

        assertThatThrownBy(() -> verifier.verifyCommittedObject(entity, "expected-crc", "expected-crc"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHECKSUM_MISMATCH);
    }

    @Test
    void verifyCommittedObject_expectedChecksumPresentWithoutAuthoritativeChecksum_failsClosed() {
        S3Properties disabledFullObjectProps = new S3Properties(
                "http://localhost:9000",
                null,
                "us-east-1",
                "access",
                "secret",
                "mediamesh-raw",
                "mediamesh-processed",
                true,
                false,
                Duration.ofMinutes(20),
                Duration.ofHours(24),
                Duration.ofDays(7),
                5_497_558_138_880L,
                65_536);
        ObjectIntegrityVerifier disabledVerifier =
                new ObjectIntegrityVerifier(storageProvider, disabledFullObjectProps);
        StorageObjectEntity entity = sampleEntity();
        when(storageProvider.headObject("mediamesh-raw", entity.getObjectKey()))
                .thenReturn(Optional.of(new ObjectMetadata(1024L, "etag-1", null)));

        assertThatThrownBy(() -> disabledVerifier.verifyCommittedObject(entity, null, "expected-crc"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHECKSUM_MISMATCH);
    }

    @Test
    void verifyCommittedObject_invalidMediaHeader_throwsInvalidMediaContainer() {
        StorageObjectEntity entity = sampleEntity();
        when(storageProvider.headObject("mediamesh-raw", entity.getObjectKey()))
                .thenReturn(Optional.of(new ObjectMetadata(1024L, "etag-1", "crc-ok")));
        when(storageProvider.readByteRange(eq("mediamesh-raw"), eq(entity.getObjectKey()), eq(0L), anyLong()))
                .thenReturn("<html>not-video</html>".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> verifier.verifyCommittedObject(entity, "crc-ok", "crc-ok"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_MEDIA_CONTAINER);
    }

    private static StorageObjectEntity sampleEntity() {
        return StorageObjectEntity.builder()
                .uploadId(UUID.randomUUID())
                .mediaId(UUID.randomUUID())
                .groupId(UUID.randomUUID())
                .libraryId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .fileName("clip.mp4")
                .bucket("mediamesh-raw")
                .objectKey("raw/g/" + UUID.randomUUID())
                .status(StorageObjectStatus.COMMITTING)
                .declaredSize(1024L)
                .partSize(1024L)
                .totalParts(1)
                .contentType("video/mp4")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
