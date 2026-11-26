package com.mediamesh.ingestion.features.uploads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mediamesh.ingestion.TestcontainersConfiguration;
import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import com.mediamesh.ingestion.features.media.delete.DeleteMediaHandler;
import com.mediamesh.ingestion.features.uploads.abort.AbortUploadHandler;
import com.mediamesh.ingestion.features.uploads.complete.CompleteUploadHandler;
import com.mediamesh.ingestion.features.uploads.complete.CompleteUploadRequest;
import com.mediamesh.ingestion.features.uploads.complete.CompleteUploadRequest.PartChecksumDto;
import com.mediamesh.ingestion.features.uploads.complete.CompleteUploadResponse;
import com.mediamesh.ingestion.features.uploads.init.InitUploadHandler;
import com.mediamesh.ingestion.features.uploads.init.InitUploadRequest;
import com.mediamesh.ingestion.features.uploads.init.InitUploadResponse;
import com.mediamesh.ingestion.features.uploads.parts.GetUploadPartsHandler;
import com.mediamesh.ingestion.features.uploads.parts.PartsBatchResponse;
import com.mediamesh.ingestion.features.uploads.presign.PresignPartsHandler;
import com.mediamesh.ingestion.features.uploads.presign.PresignPartsRequest;
import com.mediamesh.ingestion.features.uploads.presign.PresignPartsRequest.PartPresignSpec;
import com.mediamesh.ingestion.features.uploads.presign.PresignPartsResponse;
import com.mediamesh.ingestion.storage.PartGeometry;
import com.mediamesh.ingestion.storage.persistence.CleanupKind;
import com.mediamesh.ingestion.storage.persistence.StorageCleanupEntity;
import com.mediamesh.ingestion.storage.persistence.StorageCleanupRepository;
import com.mediamesh.ingestion.storage.persistence.StorageObjectEntity;
import com.mediamesh.ingestion.storage.persistence.StorageObjectRepository;
import com.mediamesh.ingestion.storage.persistence.StorageObjectStatus;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider;
import com.mediamesh.ingestion.storage.verification.Crc32cCombiner;
import com.mediamesh.ingestion.storage.verification.Crc32cCombiner.PartChecksum;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UploadSlicesIntegrationTest {

    private static final int MIB = 1024 * 1024;

    @Autowired
    private InitUploadHandler initHandler;

    @Autowired
    private PresignPartsHandler presignPartsHandler;

    @Autowired
    private GetUploadPartsHandler partsHandler;

    @Autowired
    private CompleteUploadHandler completeHandler;

    @Autowired
    private AbortUploadHandler abortHandler;

    @Autowired
    private DeleteMediaHandler deleteMediaHandler;

    @Autowired
    private StorageObjectRepository storageObjectRepository;

    @Autowired
    private StorageCleanupRepository storageCleanupRepository;

    @Autowired
    private ObjectStorageProvider objectStorageProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void fullHappyPathAcrossUploadAndDeleteSlices() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();

        byte[] part1 = mp4Bytes(8 * MIB, (byte) 0x1A);
        byte[] part2 = filledBytes(4 * MIB, (byte) 0x2B);
        String crc1 = Crc32cCombiner.computeBase64(part1);
        String crc2 = Crc32cCombiner.computeBase64(part2);
        String fullCrc32c = Crc32cCombiner.combineBase64(List.of(
                new PartChecksum(crc1, part1.length),
                new PartChecksum(crc2, part2.length)));

        InitUploadResponse allocated = initHandler.handle(userId, new InitUploadRequest(
                groupId, libraryId, "vacation.mp4", "Vacation", "video/mp4", 12L * MIB));

        assertThat(allocated.totalParts()).isEqualTo(2);
        assertThat(allocated.partSizeBytes()).isEqualTo(8L * MIB);
        assertThat(allocated.recommendedConcurrency()).isEqualTo(4);

        PresignPartsResponse presign1 = presignPartsHandler.handle(
                allocated.uploadId(), userId, new PresignPartsRequest(List.of(new PartPresignSpec(1, crc1))));
        putPresigned(presign1.parts().get(0).url(), part1, crc1);

        PartsBatchResponse batch = partsHandler.handle(allocated.uploadId(), userId);
        assertThat(batch.uploadedParts()).hasSize(1);
        assertThat(batch.uploadedParts().get(0).partNumber()).isEqualTo(1);
        assertThat(batch.uploadedParts().get(0).size()).isEqualTo(8L * MIB);

        PresignPartsResponse presign2 = presignPartsHandler.handle(
                allocated.uploadId(), userId, new PresignPartsRequest(List.of(new PartPresignSpec(2, crc2))));
        putPresigned(presign2.parts().get(0).url(), part2, crc2);

        CompleteUploadResponse committed = completeHandler.handle(
                allocated.uploadId(),
                userId,
                new CompleteUploadRequest(fullCrc32c, List.of(
                        new PartChecksumDto(1, crc1),
                        new PartChecksumDto(2, crc2))));
        assertThat(committed.verifiedSizeBytes()).isEqualTo(12L * MIB);
        assertThat(committed.etag()).isNotBlank();
        assertThat(committed.checksum()).isEqualTo(fullCrc32c);

        StorageObjectEntity persisted = storageObjectRepository.findById(allocated.uploadId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(StorageObjectStatus.COMMITTED);
        assertThat(persisted.getActualSize()).isEqualTo(12L * MIB);
        assertThat(objectStorageProvider.headObject(persisted.getBucket(), persisted.getObjectKey())).isPresent();

        deleteMediaHandler.handle(allocated.mediaId());
        StorageObjectEntity deleted = storageObjectRepository.findById(allocated.uploadId()).orElseThrow();
        assertThat(deleted.getStatus()).isEqualTo(StorageObjectStatus.DELETED);
        assertThat(deleted.getDeletedAt()).isNotNull();

        List<StorageCleanupEntity> cleanups = storageCleanupRepository.findAll().stream()
                .filter(c -> c.getObjectKey().equals(persisted.getObjectKey())
                        || c.getObjectKey().equals(PartGeometry.processedPrefix(groupId, allocated.mediaId())))
                .toList();
        assertThat(cleanups).extracting(StorageCleanupEntity::getKind)
                .containsExactlyInAnyOrder(CleanupKind.DELETE_OBJECT, CleanupKind.DELETE_PREFIX);
    }

    @Test
    void completeWithMissingPartIsNonDestructiveAndAllowsResume() throws Exception {
        UUID userId = UUID.randomUUID();
        InitUploadResponse allocated = initHandler.handle(userId, new InitUploadRequest(
                UUID.randomUUID(), UUID.randomUUID(), "resumable.mp4", null, "video/mp4", 12L * MIB));

        byte[] part1 = mp4Bytes(8 * MIB, (byte) 0x55);
        byte[] part2 = filledBytes(4 * MIB, (byte) 0x66);
        String crc1 = Crc32cCombiner.computeBase64(part1);
        String crc2 = Crc32cCombiner.computeBase64(part2);
        String fullCrc = Crc32cCombiner.combineBase64(List.of(
                new PartChecksum(crc1, part1.length),
                new PartChecksum(crc2, part2.length)));

        PresignPartsResponse presign1 = presignPartsHandler.handle(
                allocated.uploadId(), userId, new PresignPartsRequest(List.of(new PartPresignSpec(1, crc1))));
        putPresigned(presign1.parts().get(0).url(), part1, crc1);

        CompleteUploadRequest completeReq = new CompleteUploadRequest(fullCrc, List.of(
                new PartChecksumDto(1, crc1),
                new PartChecksumDto(2, crc2)));

        assertThatThrownBy(() -> completeHandler.handle(allocated.uploadId(), userId, completeReq))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARTS_MISSING);
                    assertThat(ex.getProperties()).containsEntry("missingParts", List.of(2));
                });

        StorageObjectEntity stillPending = storageObjectRepository.findById(allocated.uploadId()).orElseThrow();
        assertThat(stillPending.getStatus()).isEqualTo(StorageObjectStatus.PENDING);
        assertThat(stillPending.getChecksumMismatchCount()).isZero();

        PresignPartsResponse presign2 = presignPartsHandler.handle(
                allocated.uploadId(), userId, new PresignPartsRequest(List.of(new PartPresignSpec(2, crc2))));
        putPresigned(presign2.parts().get(0).url(), part2, crc2);

        CompleteUploadResponse committed = completeHandler.handle(allocated.uploadId(), userId, completeReq);
        assertThat(committed.verifiedSizeBytes()).isEqualTo(12L * MIB);
    }

    @Test
    void compositeMismatchRollsBackWithoutCountingRetryAndAllowsFixedComplete() throws Exception {
        UUID userId = UUID.randomUUID();
        InitUploadResponse allocated = initHandler.handle(userId, new InitUploadRequest(
                UUID.randomUUID(), UUID.randomUUID(), "composite-bug.mp4", null, "video/mp4", 8L * MIB));

        byte[] part1 = mp4Bytes(8 * MIB, (byte) 0x33);
        String realCrc = Crc32cCombiner.computeBase64(part1);
        PresignPartsResponse presign = presignPartsHandler.handle(
                allocated.uploadId(), userId, new PresignPartsRequest(List.of(new PartPresignSpec(1, realCrc))));
        putPresigned(presign.parts().get(0).url(), part1, realCrc);

        String wrongExpected = Crc32cCombiner.computeBase64(new byte[] {9, 9, 9, 9});
        CompleteUploadRequest badComposite = new CompleteUploadRequest(
                wrongExpected, List.of(new PartChecksumDto(1, realCrc)));

        assertThatThrownBy(() -> completeHandler.handle(allocated.uploadId(), userId, badComposite))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COMPOSITE_CHECKSUM_INVALID);
                    assertThat(ex.getProperties()).containsEntry("badParts", List.of());
                });

        StorageObjectEntity stillPending = storageObjectRepository.findById(allocated.uploadId()).orElseThrow();
        assertThat(stillPending.getStatus()).isEqualTo(StorageObjectStatus.PENDING);
        assertThat(stillPending.getChecksumMismatchCount()).isZero();

        CompleteUploadResponse committed = completeHandler.handle(
                allocated.uploadId(),
                userId,
                new CompleteUploadRequest(realCrc, List.of(new PartChecksumDto(1, realCrc))));
        assertThat(committed.verifiedSizeBytes()).isEqualTo(8L * MIB);
    }

    @Test
    void completeWithSpoofedMediaHeaderFailsAndEnqueuesDeleteObject() throws Exception {
        UUID userId = UUID.randomUUID();
        InitUploadResponse allocated = initHandler.handle(userId, new InitUploadRequest(
                UUID.randomUUID(), UUID.randomUUID(), "spoofed.mp4", null, "video/mp4", 8L * MIB));

        byte[] htmlPayload = filledBytes(8 * MIB, (byte) ' ');
        byte[] htmlHeader = "<html><body>not a video</body></html>".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(htmlHeader, 0, htmlPayload, 0, htmlHeader.length);
        String crc = Crc32cCombiner.computeBase64(htmlPayload);

        PresignPartsResponse presign = presignPartsHandler.handle(
                allocated.uploadId(), userId, new PresignPartsRequest(List.of(new PartPresignSpec(1, crc))));
        putPresigned(presign.parts().get(0).url(), htmlPayload, crc);

        assertThatThrownBy(() -> completeHandler.handle(
                        allocated.uploadId(),
                        userId,
                        new CompleteUploadRequest(crc, List.of(new PartChecksumDto(1, crc)))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_MEDIA_CONTAINER);

        StorageObjectEntity failed = storageObjectRepository.findById(allocated.uploadId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(StorageObjectStatus.FAILED);
        assertThat(storageCleanupRepository.findAll().stream()
                .anyMatch(c -> c.getObjectKey().equals(failed.getObjectKey())
                        && c.getKind() == CleanupKind.DELETE_OBJECT)).isTrue();
    }

    @Test
    void completeExpiredUploadReturnsGone() {
        UUID userId = UUID.randomUUID();
        InitUploadResponse allocated = initHandler.handle(userId, new InitUploadRequest(
                UUID.randomUUID(), UUID.randomUUID(), "expired.mp4", null, "video/mp4", 8L * MIB));

        jdbcTemplate.update(
                "UPDATE storage_objects SET expires_at = ? WHERE upload_id = ?",
                Timestamp.from(Instant.now().minus(5, ChronoUnit.MINUTES)),
                allocated.uploadId());

        String dummyCrc = Crc32cCombiner.computeBase64(new byte[] {1});
        assertThatThrownBy(() -> completeHandler.handle(
                        allocated.uploadId(),
                        userId,
                        new CompleteUploadRequest(dummyCrc, List.of(new PartChecksumDto(1, dummyCrc)))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.GONE);
    }

    @Test
    void doubleAbortIsIdempotentWithSingleCleanupRow() {
        UUID userId = UUID.randomUUID();
        InitUploadResponse allocated = initHandler.handle(userId, new InitUploadRequest(
                UUID.randomUUID(), UUID.randomUUID(), "abort.mp4", null, "video/mp4", 8L * MIB));

        StorageObjectEntity entity = storageObjectRepository.findById(allocated.uploadId()).orElseThrow();
        abortHandler.handle(allocated.uploadId(), userId);
        abortHandler.handle(allocated.uploadId(), userId);

        long cleanupCount = storageCleanupRepository.findAll().stream()
                .filter(c -> c.getObjectKey().equals(entity.getObjectKey())
                        && c.getKind() == CleanupKind.ABORT_MP)
                .count();
        assertThat(cleanupCount).isEqualTo(1);
    }

    @Test
    void concurrentCompleteAllowsOnlyOneWinner() throws Exception {
        UUID userId = UUID.randomUUID();
        InitUploadResponse allocated = initHandler.handle(userId, new InitUploadRequest(
                UUID.randomUUID(), UUID.randomUUID(), "race.mp4", null, "video/mp4", 8L * MIB));

        byte[] bytes = mp4Bytes(8 * MIB, (byte) 0x77);
        String crc = Crc32cCombiner.computeBase64(bytes);
        PresignPartsResponse presign = presignPartsHandler.handle(
                allocated.uploadId(), userId, new PresignPartsRequest(List.of(new PartPresignSpec(1, crc))));
        putPresigned(presign.parts().get(0).url(), bytes, crc);

        CompleteUploadRequest completeReq = new CompleteUploadRequest(
                crc, List.of(new PartChecksumDto(1, crc)));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Object>> futures = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return completeHandler.handle(allocated.uploadId(), userId, completeReq);
                } catch (ApiException ex) {
                    return ex.getErrorCode();
                }
            }));
        }

        ready.await();
        start.countDown();

        Object r1 = futures.get(0).get();
        Object r2 = futures.get(1).get();
        pool.shutdown();

        List<Object> results = List.of(r1, r2);
        assertThat(results.stream().filter(CompleteUploadResponse.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(ErrorCode.class::isInstance).findFirst())
                .hasValueSatisfying(code -> assertThat(code)
                        .isIn(ErrorCode.COMMIT_IN_PROGRESS, ErrorCode.CONFLICT));
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
