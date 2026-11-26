package com.mediamesh.ingestion.storage.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Storage-provider abstraction over S3-compatible object storage.
 * Keeps AWS SDK types out of the domain and service layers.
 */
public interface ObjectStorageProvider {

    /**
     * Initiates a multipart upload with full-object CRC32C checksum tracking
     * and returns the provider-assigned upload ID.
     */
    String createMultipart(String bucket, String objectKey, String contentType);

    /**
     * Generates a short-lived presigned PUT URL for uploading a single multipart part
     * with an exact signed {@code Content-Length} and {@code x-amz-checksum-crc32c}.
     */
    PresignedPartUrl presignPart(
            String bucket,
            String objectKey,
            String providerUploadId,
            int partNumber,
            long expectedSizeBytes,
            String crc32c,
            Duration ttl);

    /**
     * Lists a single page of parts currently stored by the provider for an in-progress multipart upload.
     */
    UploadedPartsPage listPartsPage(
            String bucket,
            String objectKey,
            String providerUploadId,
            Integer partNumberMarker,
            int maxParts);

    /**
     * Lists all parts currently stored by the provider for an in-progress multipart upload.
     */
    List<UploadedPart> listParts(String bucket, String objectKey, String providerUploadId);

    /**
     * Lists active multipart upload IDs matching the given object key prefix.
     */
    List<String> listMultipartUploads(String bucket, String objectKeyPrefix);

    /**
     * Finalizes a multipart upload from verified parts and optional full-object CRC32C checksum,
     * returning the resulting object ETag and full-object checksum.
     */
    CompleteMultipartResult completeMultipart(
            String bucket,
            String objectKey,
            String providerUploadId,
            List<CompletedPartSpec> parts,
            String expectedFullObjectCrc32c);

    /**
     * Aborts a multipart upload and discards any uploaded parts. Idempotent when already absent.
     */
    void abortMultipart(String bucket, String objectKey, String providerUploadId);

    /**
     * Retrieves object metadata if the object exists, or empty if absent.
     */
    Optional<ObjectMetadata> headObject(String bucket, String objectKey);

    /**
     * Reads an inclusive byte range from an object for lightweight header inspection.
     */
    byte[] readByteRange(String bucket, String objectKey, long startInclusive, long endInclusive);

    /**
     * Deletes a single object. Idempotent when already absent.
     */
    void deleteObject(String bucket, String objectKey);

    /**
     * Deletes all objects matching the given key prefix.
     */
    void deletePrefix(String bucket, String prefix);

    /** Presigned part upload URL, required signed headers, and its expiration timestamp. */
    record PresignedPartUrl(String url, Map<String, String> headers, Instant expiresAt) {}

    /** Paginated part listing result from the object storage provider. */
    record UploadedPartsPage(List<UploadedPart> parts, Integer nextPartNumberMarker, boolean truncated) {}

    /** Part metadata reported by the object storage provider. */
    record UploadedPart(int partNumber, long sizeBytes, String etag, String checksumCrc32c) {}

    /** Part specification passed to complete a multipart upload. */
    record CompletedPartSpec(int partNumber, String etag, String checksumCrc32c) {}

    /** Result returned after completing a multipart upload. */
    record CompleteMultipartResult(String etag, String checksumCrc32c) {}

    /** Verified metadata of a committed object. */
    record ObjectMetadata(long sizeBytes, String etag, String checksumCrc32c) {}
}
