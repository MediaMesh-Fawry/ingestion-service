package com.mediamesh.ingestion.features.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.mediamesh.ingestion.TestcontainersConfiguration;
import com.mediamesh.ingestion.config.S3Properties;
import com.mediamesh.ingestion.features.uploads.init.InitUploadHandler;
import com.mediamesh.ingestion.features.uploads.init.InitUploadRequest;
import com.mediamesh.ingestion.features.uploads.init.InitUploadResponse;
import com.mediamesh.ingestion.features.uploads.presign.PresignPartsHandler;
import com.mediamesh.ingestion.features.uploads.presign.PresignPartsRequest;
import com.mediamesh.ingestion.features.uploads.presign.PresignPartsRequest.PartPresignSpec;
import com.mediamesh.ingestion.features.uploads.presign.PresignPartsResponse;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "storage.cleanup.enabled=false")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CleanupWorkerIntegrationTest {

    private static final int MIB = 1024 * 1024;

    @Autowired
    private CleanupWorker cleanupWorker;

    @Autowired
    private InitUploadHandler initHandler;

    @Autowired
    private PresignPartsHandler presignPartsHandler;

    @Autowired
    private ObjectStorageProvider objectStorageProvider;

    @Autowired
    private StorageObjectRepository storageObjectRepository;

    @Autowired
    private StorageCleanupRepository storageCleanupRepository;

    @Autowired
    private S3Properties s3Properties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void drainCleanupQueueAbortsMultipartUploadWhenProviderUploadIdIsNull() {
        String bucket = s3Properties.rawBucket();
        String key = "raw/crash/" + UUID.randomUUID();
        String providerUploadId = objectStorageProvider.createMultipart(bucket, key, "video/mp4");
        assertThat(objectStorageProvider.listMultipartUploads(bucket, key)).contains(providerUploadId);

        StorageCleanupEntity task = storageCleanupRepository.save(StorageCleanupEntity.builder()
                .kind(CleanupKind.ABORT_MP)
                .bucket(bucket)
                .objectKey(key)
                .providerUploadId(null)
                .attempts(0)
                .runAfter(Instant.now().minus(1, ChronoUnit.MINUTES))
                .build());

        cleanupWorker.drainCleanupQueue();

        assertThat(objectStorageProvider.listMultipartUploads(bucket, key)).isEmpty();
        assertThat(storageCleanupRepository.findById(task.getId())).isEmpty();
    }

    @Test
    void expireStaleUploadsMarksExpiredAndDrainsMultipartFromRustFs() {
        InitUploadResponse allocated = initHandler.handle(UUID.randomUUID(), new InitUploadRequest(
                UUID.randomUUID(), UUID.randomUUID(), "stale.mp4", null, "video/mp4", 8L * MIB));

        StorageObjectEntity entity = storageObjectRepository.findById(allocated.uploadId()).orElseThrow();
        assertThat(objectStorageProvider.listMultipartUploads(entity.getBucket(), entity.getObjectKey()))
                .contains(entity.getProviderUploadId());

        jdbcTemplate.update(
                "UPDATE storage_objects SET expires_at = ? WHERE upload_id = ?",
                Timestamp.from(Instant.now().minus(5, ChronoUnit.MINUTES)),
                allocated.uploadId());

        int expired = cleanupWorker.expireStaleUploads();
        assertThat(expired).isGreaterThanOrEqualTo(1);

        StorageObjectEntity reloaded = storageObjectRepository.findById(allocated.uploadId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(StorageObjectStatus.EXPIRED);

        cleanupWorker.drainCleanupQueue();
        assertThat(objectStorageProvider.listMultipartUploads(entity.getBucket(), entity.getObjectKey()))
                .isEmpty();
    }

    @Test
    void recoverStuckCommitsReconcilesValidInvalidAndMissingObjects() throws Exception {
        // Case A: valid MP4 completed in S3, stuck in COMMITTING -> should recover to COMMITTED
        UUID okUserId = UUID.randomUUID();
        InitUploadResponse completedInS3 = initHandler.handle(okUserId, new InitUploadRequest(
                UUID.randomUUID(), UUID.randomUUID(), "stuck-ok.mp4", null, "video/mp4", 8L * MIB));
        byte[] validMp4 = mp4Bytes(8 * MIB, (byte) 0x44);
        String validCrc = Crc32cCombiner.computeBase64(validMp4);
        PresignPartsResponse okPresign = presignPartsHandler.handle(
                completedInS3.uploadId(),
                okUserId,
                new PresignPartsRequest(List.of(new PartPresignSpec(1, validCrc))));
        putPresigned(okPresign.parts().get(0).url(), validMp4, validCrc);

        StorageObjectEntity okEntity = storageObjectRepository.findById(completedInS3.uploadId()).orElseThrow();
        List<UploadedPart> parts = objectStorageProvider.listParts(
                okEntity.getBucket(), okEntity.getObjectKey(), okEntity.getProviderUploadId());
        objectStorageProvider.completeMultipart(
                okEntity.getBucket(),
                okEntity.getObjectKey(),
                okEntity.getProviderUploadId(),
                List.of(new CompletedPartSpec(1, parts.get(0).etag(), parts.get(0).checksumCrc32c())),
                validCrc);

        jdbcTemplate.update(
                "UPDATE storage_objects SET status = 'COMMITTING', expected_checksum = ?, updated_at = ? WHERE upload_id = ?",
                validCrc,
                Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)),
                completedInS3.uploadId());

        // Case B: spoofed non-MP4 bytes completed in S3, stuck in COMMITTING -> FAILED + DELETE_OBJECT
        UUID spoofedUserId = UUID.randomUUID();
        InitUploadResponse spoofedInS3 = initHandler.handle(spoofedUserId, new InitUploadRequest(
                UUID.randomUUID(), UUID.randomUUID(), "stuck-spoofed.mp4", null, "video/mp4", 8L * MIB));
        byte[] spoofedBytes = filledBytes(8 * MIB, (byte) 0x55);
        String spoofedCrc = Crc32cCombiner.computeBase64(spoofedBytes);
        PresignPartsResponse spoofedPresign = presignPartsHandler.handle(
                spoofedInS3.uploadId(),
                spoofedUserId,
                new PresignPartsRequest(List.of(new PartPresignSpec(1, spoofedCrc))));
        putPresigned(spoofedPresign.parts().get(0).url(), spoofedBytes, spoofedCrc);

        StorageObjectEntity spoofedEntity = storageObjectRepository.findById(spoofedInS3.uploadId()).orElseThrow();
        List<UploadedPart> spoofedParts = objectStorageProvider.listParts(
                spoofedEntity.getBucket(), spoofedEntity.getObjectKey(), spoofedEntity.getProviderUploadId());
        objectStorageProvider.completeMultipart(
                spoofedEntity.getBucket(),
                spoofedEntity.getObjectKey(),
                spoofedEntity.getProviderUploadId(),
                List.of(new CompletedPartSpec(
                        1, spoofedParts.get(0).etag(), spoofedParts.get(0).checksumCrc32c())),
                Crc32cCombiner.computeBase64(spoofedBytes));

        jdbcTemplate.update(
                "UPDATE storage_objects SET status = 'COMMITTING', updated_at = ? WHERE upload_id = ?",
                Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)),
                spoofedInS3.uploadId());

        // Case C: missing in S3, stuck in COMMITTING -> should transition to FAILED + enqueue ABORT_MP
        InitUploadResponse missingInS3 = initHandler.handle(UUID.randomUUID(), new InitUploadRequest(
                UUID.randomUUID(), UUID.randomUUID(), "stuck-missing.mp4", null, "video/mp4", 8L * MIB));
        StorageObjectEntity missingEntity = storageObjectRepository.findById(missingInS3.uploadId()).orElseThrow();

        jdbcTemplate.update(
                "UPDATE storage_objects SET status = 'COMMITTING', updated_at = ? WHERE upload_id = ?",
                Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)),
                missingInS3.uploadId());

        int recovered = cleanupWorker.recoverStuckCommits();
        assertThat(recovered).isGreaterThanOrEqualTo(3);

        StorageObjectEntity okAfter = storageObjectRepository.findById(completedInS3.uploadId()).orElseThrow();
        assertThat(okAfter.getStatus()).isEqualTo(StorageObjectStatus.COMMITTED);
        assertThat(okAfter.getActualSize()).isEqualTo(8L * MIB);

        StorageObjectEntity spoofedAfter = storageObjectRepository.findById(spoofedInS3.uploadId()).orElseThrow();
        assertThat(spoofedAfter.getStatus()).isEqualTo(StorageObjectStatus.FAILED);
        assertThat(storageCleanupRepository.findAll().stream()
                .anyMatch(c -> c.getObjectKey().equals(spoofedEntity.getObjectKey())
                        && c.getKind() == CleanupKind.DELETE_OBJECT)).isTrue();

        StorageObjectEntity missingAfter = storageObjectRepository.findById(missingInS3.uploadId()).orElseThrow();
        assertThat(missingAfter.getStatus()).isEqualTo(StorageObjectStatus.FAILED);
        assertThat(storageCleanupRepository.findAll().stream()
                .anyMatch(c -> c.getObjectKey().equals(missingEntity.getObjectKey())
                        && c.getKind() == CleanupKind.ABORT_MP)).isTrue();
    }

    @Test
    void failedCleanupTaskIncrementsAttemptsAndReschedulesWithBackoff() {
        StorageCleanupEntity failingTask = storageCleanupRepository.save(StorageCleanupEntity.builder()
                .kind(CleanupKind.DELETE_PREFIX)
                .bucket("nonexistent-bucket-" + UUID.randomUUID())
                .objectKey("raw/missing/")
                .attempts(0)
                .runAfter(Instant.now().minus(1, ChronoUnit.MINUTES))
                .build());

        cleanupWorker.drainCleanupQueue();

        StorageCleanupEntity reloaded = storageCleanupRepository.findById(failingTask.getId()).orElseThrow();
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getRunAfter()).isAfter(Instant.now().plusSeconds(10));
    }

    private void putPresigned(String url, byte[] bytes, String crc32c) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("x-amz-checksum-crc32c", crc32c)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isBetween(200, 299);
    }

    private static byte[] mp4Bytes(int length, byte fill) {
        byte[] data = filledBytes(length, fill);
        byte[] header = new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        System.arraycopy(header, 0, data, 0, header.length);
        return data;
    }

    private static byte[] filledBytes(int length, byte value) {
        byte[] data = new byte[length];
        Arrays.fill(data, value);
        return data;
    }
}
