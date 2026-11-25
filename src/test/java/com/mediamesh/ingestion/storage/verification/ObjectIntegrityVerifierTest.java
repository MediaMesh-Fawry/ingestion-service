package com.mediamesh.ingestion.storage.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private final ObjectStorageProvider objectStorageProvider = mock(ObjectStorageProvider.class);
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
    private final ObjectIntegrityVerifier verifier = new ObjectIntegrityVerifier(objectStorageProvider, properties);

    @Test
    void verifyCommittedObject_succeedsWithMatchingMetadataChecksumAndSignature() {
        StorageObjectEntity entity = sampleEntity(12L, "crc123==");
        byte[] mp4Header = new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};

        when(objectStorageProvider.headObject("mediamesh-raw", entity.getObjectKey()))
                .thenReturn(Optional.of(new ObjectMetadata(12L, "etag-1", "crc123==")));
        when(objectStorageProvider.readByteRange("mediamesh-raw", entity.getObjectKey(), 0L, 11L))
                .thenReturn(mp4Header);

        VerifiedObject verified = verifier.verifyCommittedObject(entity, "crc123==");
        assertThat(verified).isEqualTo(new VerifiedObject(12L, "etag-1", "crc123==", "CRC32C"));
    }

    @Test
    void verifyCommittedObject_throwsStorageUnavailableWhenObjectMissing() {
        StorageObjectEntity entity = sampleEntity(12L, "crc123==");
        when(objectStorageProvider.headObject("mediamesh-raw", entity.getObjectKey()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> verifier.verifyCommittedObject(entity, "crc123=="))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.STORAGE_UNAVAILABLE);
    }

    @Test
    void verifyCommittedObject_throwsSizeMismatchWhenDeclaredSizeDiffers() {
        StorageObjectEntity entity = sampleEntity(100L, "crc123==");
        when(objectStorageProvider.headObject("mediamesh-raw", entity.getObjectKey()))
                .thenReturn(Optional.of(new ObjectMetadata(50L, "etag-1", "crc123==")));

        assertThatThrownBy(() -> verifier.verifyCommittedObject(entity, "crc123=="))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SIZE_MISMATCH);
    }

    @Test
    void verifyCommittedObject_throwsChecksumMismatchWhenExpectedDiffers() {
        StorageObjectEntity entity = sampleEntity(12L, "expectedCrc==");
        when(objectStorageProvider.headObject("mediamesh-raw", entity.getObjectKey()))
                .thenReturn(Optional.of(new ObjectMetadata(12L, "etag-1", "actualCrc==")));

        assertThatThrownBy(() -> verifier.verifyCommittedObject(entity, "actualCrc=="))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHECKSUM_MISMATCH);
    }

    @Test
    void verifyCommittedObject_throwsInvalidMediaContainerWhenSignatureIsSpoofed() {
        StorageObjectEntity entity = sampleEntity(12L, "crc123==");
        when(objectStorageProvider.headObject("mediamesh-raw", entity.getObjectKey()))
                .thenReturn(Optional.of(new ObjectMetadata(12L, "etag-1", "crc123==")));
        when(objectStorageProvider.readByteRange("mediamesh-raw", entity.getObjectKey(), 0L, 11L))
                .thenReturn("<html>hello!".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> verifier.verifyCommittedObject(entity, "crc123=="))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_MEDIA_CONTAINER);
    }

    private static StorageObjectEntity sampleEntity(long declaredSize, String expectedChecksum) {
        UUID uploadId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        return StorageObjectEntity.builder()
                .uploadId(uploadId)
                .mediaId(mediaId)
                .groupId(UUID.randomUUID())
                .libraryId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .fileName("clip.mp4")
                .bucket("mediamesh-raw")
                .objectKey("raw/g/" + mediaId)
                .status(StorageObjectStatus.COMMITTING)
                .declaredSize(declaredSize)
                .partSize(8_388_608L)
                .totalParts(1)
                .contentType("video/mp4")
                .expectedChecksum(expectedChecksum)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
