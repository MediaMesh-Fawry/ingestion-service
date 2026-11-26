package com.mediamesh.ingestion.storage.verification;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.config.S3Properties;
import com.mediamesh.ingestion.storage.persistence.StorageObjectEntity;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.ObjectMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Authoritative post-assembly object integrity verifier shared between
 * {@code CompleteUploadHandler} and {@code CleanupWorker.recoverStuckCommits}.
 */
@Component
@RequiredArgsConstructor
public class ObjectIntegrityVerifier {

    private final ObjectStorageProvider objectStorageProvider;
    private final S3Properties s3Properties;

    /**
     * Verifies final object size, full-object CRC32C checksum against {@code expectedChecksum},
     * and container magic-byte signature via a bounded range read without proxying the file.
     */
    public VerifiedObject verifyCommittedObject(StorageObjectEntity entity, String expectedChecksum) {
        ObjectMetadata metadata = objectStorageProvider.headObject(entity.getBucket(), entity.getObjectKey())
                .orElseThrow(() -> ApiException.storageUnavailable("Completed object could not be verified", null));

        if (metadata.sizeBytes() != entity.getDeclaredSize()) {
            throw ApiException.sizeMismatch(entity.getDeclaredSize(), metadata.sizeBytes());
        }

        if (s3Properties.fullObjectChecksumEnabled() && metadata.checksumCrc32c() == null) {
            throw ApiException.checksumMismatch(
                    "Object storage did not return full-object CRC32C checksum");
        }

        if (expectedChecksum != null
                && metadata.checksumCrc32c() != null
                && !metadata.checksumCrc32c().equals(expectedChecksum)) {
            throw ApiException.checksumMismatch(
                    "Object storage CRC32C (" + metadata.checksumCrc32c()
                            + ") does not match expected CRC32C (" + expectedChecksum + ")");
        }

        long maxEnd = Math.min((long) s3Properties.headerInspectBytes() - 1L, metadata.sizeBytes() - 1L);
        byte[] headerBytes = objectStorageProvider.readByteRange(
                entity.getBucket(), entity.getObjectKey(), 0L, maxEnd);
        MediaSignatureVerifier.verify(entity.getContentType(), headerBytes);

        String authoritativeChecksum = metadata.checksumCrc32c() != null
                ? metadata.checksumCrc32c()
                : expectedChecksum;
        String finalChecksum = authoritativeChecksum != null ? authoritativeChecksum : metadata.etag();
        String algorithm = authoritativeChecksum != null ? "CRC32C" : null;
        return new VerifiedObject(metadata.sizeBytes(), metadata.etag(), finalChecksum, algorithm);
    }

    /** Verified metadata of a committed object. */
    public record VerifiedObject(
            long sizeBytes, String etag, String checksum, String checksumAlgorithm) {}
}
