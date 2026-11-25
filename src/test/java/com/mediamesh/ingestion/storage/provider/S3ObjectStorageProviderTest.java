package com.mediamesh.ingestion.storage.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import com.mediamesh.ingestion.config.S3Properties;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.CompletedPartSpec;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.ObjectMetadata;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.UploadedPart;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.paginators.ListPartsIterable;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3ObjectStorageProviderTest {

    private final S3Client s3Client = mock(S3Client.class);
    private final S3Presigner s3Presigner = mock(S3Presigner.class);
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
    private final S3ObjectStorageProvider provider = new S3ObjectStorageProvider(s3Client, s3Presigner, properties);

    @Test
    void headObject_returnsEmptyWhenNoSuchKey() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());

        Optional<ObjectMetadata> result = provider.headObject("mediamesh-raw", "raw/g/m");
        assertThat(result).isEmpty();
    }

    @Test
    void headObject_stripsQuotesFromEtag() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(1024L)
                .eTag("\"abc123-1\"")
                .checksumCRC32C("crc==")
                .build());

        Optional<ObjectMetadata> result = provider.headObject("mediamesh-raw", "raw/g/m");
        assertThat(result).contains(new ObjectMetadata(1024L, "abc123-1", "crc=="));
    }

    @Test
    void abortMultipart_isIdempotentOnNoSuchUpload() {
        S3Exception noSuchUpload = (S3Exception) S3Exception.builder()
                .statusCode(404)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchUpload").build())
                .build();
        when(s3Client.abortMultipartUpload(any(AbortMultipartUploadRequest.class))).thenThrow(noSuchUpload);

        assertThatCode(() -> provider.abortMultipart("mediamesh-raw", "raw/g/m", "upload-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void createMultipart_translatesSdkExceptionWithoutLeakingRawMessage() {
        when(s3Client.createMultipartUpload(any(software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest.class)))
                .thenThrow(SdkClientException.create("internal endpoint secret-host:9000 refused"));

        assertThatThrownBy(() -> provider.createMultipart("mediamesh-raw", "raw/g/m", "video/mp4"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.STORAGE_UNAVAILABLE);
                    assertThat(apiEx.getMessage()).doesNotContain("secret-host");
                });
    }

    @Test
    void completeMultipart_translatesChecksumErrorToChecksumMismatch() {
        S3Exception checksumEx = (S3Exception) S3Exception.builder()
                .statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("BadDigest")
                        .errorMessage("The Content-MD5 or checksum you specified did not match")
                        .build())
                .build();
        when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class))).thenThrow(checksumEx);

        assertThatThrownBy(() -> provider.completeMultipart(
                        "mediamesh-raw",
                        "raw/g/m",
                        "upload-1",
                        List.of(new CompletedPartSpec(1, "etag1", "crc1")),
                        "expectedCrc"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHECKSUM_MISMATCH);
    }

    @Test
    void listParts_collectsAllPagesAndStripsEtagQuotes() {
        ListPartsRequest req = ListPartsRequest.builder()
                .bucket("mediamesh-raw")
                .key("raw/g/m")
                .uploadId("u1")
                .build();
        ListPartsIterable iterable = new ListPartsIterable(s3Client, req);
        when(s3Client.listPartsPaginator(any(ListPartsRequest.class))).thenReturn(iterable);
        when(s3Client.listParts(any(ListPartsRequest.class)))
                .thenReturn(ListPartsResponse.builder()
                        .isTruncated(true)
                        .nextPartNumberMarker(1)
                        .parts(Part.builder().partNumber(1).size(8_388_608L).eTag("\"e1\"").checksumCRC32C("c1").build())
                        .build())
                .thenReturn(ListPartsResponse.builder()
                        .isTruncated(false)
                        .parts(Part.builder().partNumber(2).size(1024L).eTag("\"e2\"").checksumCRC32C("c2").build())
                        .build());

        List<UploadedPart> parts = provider.listParts("mediamesh-raw", "raw/g/m", "u1");
        assertThat(parts).containsExactly(
                new UploadedPart(1, 8_388_608L, "e1", "c1"),
                new UploadedPart(2, 1024L, "e2", "c2"));
    }
}
