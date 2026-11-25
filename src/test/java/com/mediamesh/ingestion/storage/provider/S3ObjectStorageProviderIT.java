package com.mediamesh.ingestion.storage.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mediamesh.ingestion.TestcontainersConfiguration;
import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import com.mediamesh.ingestion.config.S3Properties;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.CompleteMultipartResult;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.CompletedPartSpec;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.ObjectMetadata;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.PresignedPartUrl;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.UploadedPart;
import com.mediamesh.ingestion.storage.verification.Crc32cCombiner;
import com.mediamesh.ingestion.storage.verification.Crc32cCombiner.PartChecksum;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class S3ObjectStorageProviderIT {

    private static final int MIB = 1024 * 1024;

    @Autowired
    private ObjectStorageProvider storageProvider;

    @Autowired
    private S3Properties s3Properties;

    @Autowired
    private S3Client s3Client;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void multipartUploadLifecyclePresignListCompleteHeadRangeReadAndDelete() throws Exception {
        String bucket = s3Properties.rawBucket();
        String key = "it/" + UUID.randomUUID() + "/sample.mp4";

        String uploadId = storageProvider.createMultipart(bucket, key, "video/mp4");
        assertThat(uploadId).isNotBlank();
        assertThat(storageProvider.listMultipartUploads(bucket, key)).contains(uploadId);

        byte[] part1Bytes = filledBytes(8 * MIB, (byte) 0x11);
        byte[] part2Bytes = filledBytes(4 * MIB, (byte) 0x22);
        String crc1 = Crc32cCombiner.computeBase64(part1Bytes);
        String crc2 = Crc32cCombiner.computeBase64(part2Bytes);
        String fullCrc = Crc32cCombiner.combineBase64(List.of(
                new PartChecksum(crc1, part1Bytes.length),
                new PartChecksum(crc2, part2Bytes.length)));

        PresignedPartUrl part1Url = storageProvider.presignPart(
                bucket, key, uploadId, 1, part1Bytes.length, Duration.ofMinutes(5));
        PresignedPartUrl part2Url = storageProvider.presignPart(
                bucket, key, uploadId, 2, part2Bytes.length, Duration.ofMinutes(5));
        assertThat(part1Url.url()).startsWith("http");
        assertThat(part1Url.expiresAt()).isAfter(Instant.now());

        // Mismatched Content-Length is rejected at the storage edge
        HttpResponse<String> badSizeResponse = putPresignedPartRaw(part1Url.url(), filledBytes(1024, (byte) 0x11), crc1);
        assertThat(badSizeResponse.statusCode()).isBetween(400, 499);

        String putEtag1 = putPresignedPart(part1Url.url(), part1Bytes, crc1);
        String putEtag2 = putPresignedPart(part2Url.url(), part2Bytes, crc2);
        assertThat(putEtag1).isNotBlank();
        assertThat(putEtag2).isNotBlank();

        List<UploadedPart> parts = storageProvider.listParts(bucket, key, uploadId);
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).partNumber()).isEqualTo(1);
        assertThat(parts.get(0).sizeBytes()).isEqualTo(8L * MIB);
        assertThat(parts.get(1).partNumber()).isEqualTo(2);
        assertThat(parts.get(1).sizeBytes()).isEqualTo(4L * MIB);

        CompleteMultipartResult completeResult = storageProvider.completeMultipart(
                bucket,
                key,
                uploadId,
                List.of(
                        new CompletedPartSpec(2, parts.get(1).etag(), parts.get(1).checksumCrc32c()),
                        new CompletedPartSpec(1, parts.get(0).etag(), parts.get(0).checksumCrc32c())),
                fullCrc);
        assertThat(completeResult.etag()).isNotBlank();

        Optional<ObjectMetadata> metadata = storageProvider.headObject(bucket, key);
        assertThat(metadata).isPresent();
        assertThat(metadata.get().sizeBytes()).isEqualTo(12L * MIB);
        assertThat(metadata.get().etag()).isNotBlank();

        byte[] headerSlice = storageProvider.readByteRange(bucket, key, 0, 15);
        assertThat(headerSlice).hasSize(16);
        assertThat(headerSlice).containsExactly(
                0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11,
                0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11);

        storageProvider.deleteObject(bucket, key);
        assertThat(storageProvider.headObject(bucket, key)).isEmpty();
        assertThatCode(() -> storageProvider.deleteObject(bucket, key)).doesNotThrowAnyException();
    }

    @Test
    void abortMultipartIsIdempotent() throws Exception {
        String bucket = s3Properties.rawBucket();
        String key = "it/" + UUID.randomUUID() + "/aborted.mp4";

        String uploadId = storageProvider.createMultipart(bucket, key, "video/mp4");
        PresignedPartUrl part1Url = storageProvider.presignPart(
                bucket, key, uploadId, 1, 1024L, Duration.ofMinutes(5));
        byte[] bytes = filledBytes(1024, (byte) 0x33);
        putPresignedPart(part1Url.url(), bytes, Crc32cCombiner.computeBase64(bytes));

        storageProvider.abortMultipart(bucket, key, uploadId);
        assertThat(storageProvider.listMultipartUploads(bucket, key)).doesNotContain(uploadId);

        assertThatCode(() -> storageProvider.abortMultipart(bucket, key, uploadId))
                .doesNotThrowAnyException();
    }

    @Test
    void deletePrefixRemovesAllMatchingObjects() {
        String bucket = s3Properties.processedBucket();
        String prefix = "it/" + UUID.randomUUID() + "/";
        String keyA = prefix + "720p/segment0.ts";
        String keyB = prefix + "1080p/segment1.ts";

        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(keyA).build(),
                RequestBody.fromBytes(new byte[] {1, 2, 3}));
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(keyB).build(),
                RequestBody.fromBytes(new byte[] {4, 5, 6}));

        assertThat(storageProvider.headObject(bucket, keyA)).isPresent();
        assertThat(storageProvider.headObject(bucket, keyB)).isPresent();

        storageProvider.deletePrefix(bucket, prefix);

        assertThat(storageProvider.headObject(bucket, keyA)).isEmpty();
        assertThat(storageProvider.headObject(bucket, keyB)).isEmpty();
    }

    private String putPresignedPart(String url, byte[] bytes, String crc32cBase64) throws Exception {
        HttpResponse<String> response = putPresignedPartRaw(url, bytes, crc32cBase64);
        assertThat(response.statusCode())
                .withFailMessage("Expected 2xx but got %d body=%s", response.statusCode(), response.body())
                .isBetween(200, 299);
        return response.headers().firstValue("ETag").orElse("");
    }

    private HttpResponse<String> putPresignedPartRaw(String url, byte[] bytes, String crc32cBase64) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] filledBytes(int length, byte value) {
        byte[] data = new byte[length];
        java.util.Arrays.fill(data, value);
        return data;
    }
}
