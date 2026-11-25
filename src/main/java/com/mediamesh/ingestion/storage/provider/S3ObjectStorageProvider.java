package com.mediamesh.ingestion.storage.provider;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.config.S3Properties;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.ChecksumType;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

/**
 * S3-compatible {@link ObjectStorageProvider} backed by AWS SDK v2
 * {@link S3Client} and {@link S3Presigner}.
 * Translates SDK exceptions into safe {@link ApiException} errors without
 * leaking credentials or raw details.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3ObjectStorageProvider implements ObjectStorageProvider {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties s3Properties;

    @Override
    public String createMultipart(String bucket, String objectKey, String contentType) {
        try {
            var builder = CreateMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType(contentType);
            if (s3Properties.fullObjectChecksumEnabled()) {
                builder.checksumAlgorithm(ChecksumAlgorithm.CRC32_C)
                        .checksumType(ChecksumType.FULL_OBJECT);
            }
            var response = s3Client.createMultipartUpload(builder.build());
            return response.uploadId();
        } catch (SdkException ex) {
            log.error("Failed to create multipart upload bucket={} key={}", bucket, objectKey, ex);
            throw ApiException.storageUnavailable("Object storage is temporarily unavailable", ex);
        }
    }

    @Override
    public PresignedPartUrl presignPart(
            String bucket,
            String objectKey,
            String providerUploadId,
            int partNumber,
            long expectedSizeBytes,
            Duration ttl) {
        try {
            Instant expiresAt = Instant.now().plus(ttl);
            var uploadPartRequest = UploadPartRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .uploadId(providerUploadId)
                    .partNumber(partNumber)
                    .contentLength(expectedSizeBytes)
                    .build();
            var presignRequest = UploadPartPresignRequest.builder()
                    .signatureDuration(ttl)
                    .uploadPartRequest(uploadPartRequest)
                    .build();
            PresignedUploadPartRequest presigned = s3Presigner.presignUploadPart(presignRequest);
            return new PresignedPartUrl(presigned.url().toString(), expiresAt);
        } catch (SdkException ex) {
            log.error(
                    "Failed to presign part bucket={} key={} partNumber={}",
                    bucket,
                    objectKey,
                    partNumber,
                    ex);
            throw ApiException.storageUnavailable("Object storage is temporarily unavailable", ex);
        }
    }

    @Override
    public List<UploadedPart> listParts(String bucket, String objectKey, String providerUploadId) {
        try {
            var request = ListPartsRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .uploadId(providerUploadId)
                    .build();
            return s3Client.listPartsPaginator(request).stream()
                    .flatMap(page -> page.parts().stream())
                    .map(part -> new UploadedPart(
                            part.partNumber(),
                            part.size(),
                            stripQuotes(part.eTag()),
                            part.checksumCRC32C()))
                    .toList();
        } catch (SdkException ex) {
            log.error(
                    "Failed to list multipart parts bucket={} key={} uploadId={}",
                    bucket,
                    objectKey,
                    providerUploadId,
                    ex);
            throw ApiException.storageUnavailable("Object storage is temporarily unavailable", ex);
        }
    }

    @Override
    public List<String> listMultipartUploads(String bucket, String objectKeyPrefix) {
        try {
            var request = ListMultipartUploadsRequest.builder()
                    .bucket(bucket)
                    .prefix(objectKeyPrefix)
                    .build();
            return s3Client.listMultipartUploadsPaginator(request).stream()
                    .flatMap(page -> page.uploads().stream())
                    .map(MultipartUpload::uploadId)
                    .toList();
        } catch (SdkException ex) {
            log.error(
                    "Failed to list multipart uploads bucket={} prefix={}",
                    bucket,
                    objectKeyPrefix,
                    ex);
            throw ApiException.storageUnavailable("Object storage is temporarily unavailable", ex);
        }
    }

    @Override
    public CompleteMultipartResult completeMultipart(
            String bucket,
            String objectKey,
            String providerUploadId,
            List<CompletedPartSpec> parts,
            String expectedFullObjectCrc32c) {
        try {
            List<CompletedPart> completedParts = parts.stream()
                    .sorted(Comparator.comparingInt(CompletedPartSpec::partNumber))
                    .map(part -> {
                        var builder = CompletedPart.builder()
                                .partNumber(part.partNumber())
                                .eTag(stripQuotes(part.etag()));
                        if (part.checksumCrc32c() != null && !part.checksumCrc32c().isBlank()) {
                            builder.checksumCRC32C(part.checksumCrc32c());
                        }
                        return builder.build();
                    })
                    .toList();

            var reqBuilder = CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .uploadId(providerUploadId)
                    .multipartUpload(CompletedMultipartUpload.builder()
                            .parts(completedParts)
                            .build());
            if (s3Properties.fullObjectChecksumEnabled()
                    && expectedFullObjectCrc32c != null
                    && !expectedFullObjectCrc32c.isBlank()) {
                reqBuilder.checksumType(ChecksumType.FULL_OBJECT)
                        .checksumCRC32C(expectedFullObjectCrc32c);
            }

            var response = s3Client.completeMultipartUpload(reqBuilder.build());
            return new CompleteMultipartResult(stripQuotes(response.eTag()), response.checksumCRC32C());
        } catch (S3Exception ex) {
            if (isChecksumFailure(ex)) {
                log.warn(
                        "Object storage rejected multipart completion due to checksum mismatch bucket={} key={}",
                        bucket,
                        objectKey,
                        ex);
                throw ApiException.checksumMismatch(
                        "Object storage rejected multipart completion: full-object CRC32C mismatch");
            }
            log.error(
                    "Failed to complete multipart upload bucket={} key={} uploadId={}",
                    bucket,
                    objectKey,
                    providerUploadId,
                    ex);
            throw ApiException.storageUnavailable("Object storage is temporarily unavailable", ex);
        } catch (SdkException ex) {
            log.error(
                    "Failed to complete multipart upload bucket={} key={} uploadId={}",
                    bucket,
                    objectKey,
                    providerUploadId,
                    ex);
            throw ApiException.storageUnavailable("Object storage is temporarily unavailable", ex);
        }
    }

    @Override
    public void abortMultipart(String bucket, String objectKey, String providerUploadId) {
        try {
            var request = AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .uploadId(providerUploadId)
                    .build();
            s3Client.abortMultipartUpload(request);
        } catch (S3Exception ex) {
            if (isNotFound(ex, "NoSuchUpload")) {
                log.debug(
                        "Multipart upload already absent during abort bucket={} key={} uploadId={}",
                        bucket,
                        objectKey,
                        providerUploadId);
                return;
            }
            log.error(
                    "Failed to abort multipart upload bucket={} key={} uploadId={}",
                    bucket,
                    objectKey,
                    providerUploadId,
                    ex);
            throw ApiException.storageUnavailable("Object storage is temporarily unavailable", ex);
        } catch (SdkException ex) {
            log.error(
                    "Failed to abort multipart upload bucket={} key={} uploadId={}",
                    bucket,
                    objectKey,
                    providerUploadId,
                    ex);
            throw ApiException.storageUnavailable("Object storage is temporarily unavailable", ex);
        }
    }

    @Override
    public Optional<ObjectMetadata> headObject(String bucket, String objectKey) {
        try {
            var request = HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .checksumMode(ChecksumMode.ENABLED)
                    .build();
            HeadObjectResponse response = s3Client.headObject(request);
            return Optional.of(new ObjectMetadata(
                    response.contentLength(),
                    stripQuotes(response.eTag()),
                    response.checksumCRC32C()));
        } catch (NoSuchKeyException ex) {
            return Optional.empty();
        } catch (S3Exception ex) {
            if (isNotFound(ex, "NoSuchKey")) {
                return Optional.empty();
            }
            log.error("Failed to head object bucket={} key={}", bucket, objectKey, ex);
            throw ApiException.storageUnavailable("Object storage is temporarily unavailable", ex);
        } catch (SdkException ex) {
            log.error("Failed to head object bucket={} key={}", bucket, objectKey, ex);
            throw ApiException.storageUnavailable("Object storage is temporarily unavailable", ex);
        }
    }

    @Override
    public byte[] readByteRange(String bucket, String objectKey, long startInclusive, long endInclusive) {
        try {
            var request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .range("bytes=" + startInclusive + "-" + endInclusive)
                    .build();
            return s3Client.getObjectAsBytes(request).asByteArray();
        } catch (SdkException ex) {
            log.error(
                    "Failed to read byte range bucket={} key={} range={}-{}",
                    bucket,
                    objectKey,
                    startInclusive,
                    endInclusive,
                    ex);
            throw ApiException.storageUnavailable("Object storage is temporarily unavailable", ex);
        }
    }

    @Override
    public void deleteObject(String bucket, String objectKey) {
        try {
            var request = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build();
            s3Client.deleteObject(request);
        } catch (NoSuchKeyException ex) {
            log.debug("Object already absent during delete bucket={} key={}", bucket, objectKey);
        } catch (S3Exception ex) {
            if (isNotFound(ex, "NoSuchKey")) {
                log.debug("Object already absent during delete bucket={} key={}", bucket, objectKey);
                return;
            }
            log.error("Failed to delete object bucket={} key={}", bucket, objectKey, ex);
            throw ApiException.storageUnavailable("Object storage is temporarily unavailable", ex);
        } catch (SdkException ex) {
            log.error("Failed to delete object bucket={} key={}", bucket, objectKey, ex);
            throw ApiException.storageUnavailable("Object storage is temporarily unavailable", ex);
        }
    }

    @Override
    public void deletePrefix(String bucket, String prefix) {
        try {
            var listRequest = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .build();
            for (var page : s3Client.listObjectsV2Paginator(listRequest)) {
                if (page.contents().isEmpty()) {
                    continue;
                }
                List<ObjectIdentifier> toDelete = page.contents().stream()
                        .map(obj -> ObjectIdentifier.builder().key(obj.key()).build())
                        .toList();
                var deleteRequest = DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(toDelete).quiet(true).build())
                        .build();
                s3Client.deleteObjects(deleteRequest);
            }
        } catch (SdkException ex) {
            log.error("Failed to delete prefix bucket={} prefix={}", bucket, prefix, ex);
            throw ApiException.storageUnavailable("Object storage is temporarily unavailable", ex);
        }
    }

    private static boolean isNotFound(S3Exception ex, String expectedErrorCode) {
        if (ex.statusCode() == 404) {
            return true;
        }
        return ex.awsErrorDetails() != null
                && expectedErrorCode.equals(ex.awsErrorDetails().errorCode());
    }

    private static boolean isChecksumFailure(S3Exception ex) {
        if (ex.statusCode() != 400) {
            return false;
        }
        String code = ex.awsErrorDetails() != null ? ex.awsErrorDetails().errorCode() : "";
        String msg = ex.awsErrorDetails() != null && ex.awsErrorDetails().errorMessage() != null
                ? ex.awsErrorDetails().errorMessage()
                : "";
        return "BadDigest".equalsIgnoreCase(code)
                || "InvalidDigest".equalsIgnoreCase(code)
                || "XAmzContentChecksumMismatch".equalsIgnoreCase(code)
                || msg.toLowerCase().contains("checksum");
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
