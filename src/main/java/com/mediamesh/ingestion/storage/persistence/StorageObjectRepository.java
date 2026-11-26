package com.mediamesh.ingestion.storage.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link StorageObjectEntity} providing guarded atomic state transitions
 * and sweep queries for expired or stuck uploads.
 */
public interface StorageObjectRepository extends JpaRepository<StorageObjectEntity, UUID> {

    Optional<StorageObjectEntity> findByMediaId(UUID mediaId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StorageObjectEntity s
            SET s.status = com.mediamesh.ingestion.storage.persistence.StorageObjectStatus.PENDING,
                s.providerUploadId = :providerUploadId,
                s.updatedAt = :now
            WHERE s.uploadId = :uploadId
              AND s.status = com.mediamesh.ingestion.storage.persistence.StorageObjectStatus.ALLOCATING
            """)
    int markPendingWithProviderUploadId(
            @Param("uploadId") UUID uploadId,
            @Param("providerUploadId") String providerUploadId,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StorageObjectEntity s
            SET s.status = :target,
                s.updatedAt = :now
            WHERE s.uploadId = :uploadId
              AND s.status IN :allowedFrom
            """)
    int transitionStatus(
            @Param("uploadId") UUID uploadId,
            @Param("allowedFrom") Collection<StorageObjectStatus> allowedFrom,
            @Param("target") StorageObjectStatus target,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StorageObjectEntity s
            SET s.status = com.mediamesh.ingestion.storage.persistence.StorageObjectStatus.COMMITTING,
                s.expectedChecksum = :expectedChecksum,
                s.updatedAt = :now
            WHERE s.uploadId = :uploadId
              AND s.status = com.mediamesh.ingestion.storage.persistence.StorageObjectStatus.PENDING
              AND s.expiresAt > :now
            """)
    int claimForCommit(
            @Param("uploadId") UUID uploadId,
            @Param("expectedChecksum") String expectedChecksum,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StorageObjectEntity s
            SET s.status = com.mediamesh.ingestion.storage.persistence.StorageObjectStatus.PENDING,
                s.expectedChecksum = null,
                s.updatedAt = :now
            WHERE s.uploadId = :uploadId
              AND s.status = com.mediamesh.ingestion.storage.persistence.StorageObjectStatus.COMMITTING
            """)
    int rollbackCommitToPending(@Param("uploadId") UUID uploadId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StorageObjectEntity s
            SET s.status = com.mediamesh.ingestion.storage.persistence.StorageObjectStatus.PENDING,
                s.checksumMismatchCount = s.checksumMismatchCount + 1,
                s.expectedChecksum = null,
                s.updatedAt = :now
            WHERE s.uploadId = :uploadId
              AND s.status = com.mediamesh.ingestion.storage.persistence.StorageObjectStatus.COMMITTING
            """)
    int incrementMismatchAndRollback(@Param("uploadId") UUID uploadId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StorageObjectEntity s
            SET s.status = com.mediamesh.ingestion.storage.persistence.StorageObjectStatus.FAILED,
                s.checksumMismatchCount = s.checksumMismatchCount + 1,
                s.updatedAt = :now
            WHERE s.uploadId = :uploadId
              AND s.status = com.mediamesh.ingestion.storage.persistence.StorageObjectStatus.COMMITTING
            """)
    int incrementMismatchAndFail(@Param("uploadId") UUID uploadId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StorageObjectEntity s
            SET s.status = com.mediamesh.ingestion.storage.persistence.StorageObjectStatus.COMMITTED,
                s.actualSize = :actualSize,
                s.etag = :etag,
                s.checksum = :checksum,
                s.checksumAlgorithm = :checksumAlgorithm,
                s.updatedAt = :now
            WHERE s.uploadId = :uploadId
              AND s.status = com.mediamesh.ingestion.storage.persistence.StorageObjectStatus.COMMITTING
            """)
    int markCommittedWithActualSize(
            @Param("uploadId") UUID uploadId,
            @Param("actualSize") long actualSize,
            @Param("etag") String etag,
            @Param("checksum") String checksum,
            @Param("checksumAlgorithm") String checksumAlgorithm,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StorageObjectEntity s
            SET s.status = com.mediamesh.ingestion.storage.persistence.StorageObjectStatus.DELETED,
                s.deletedAt = :deletedAt,
                s.updatedAt = :now
            WHERE s.mediaId = :mediaId
              AND s.status = com.mediamesh.ingestion.storage.persistence.StorageObjectStatus.COMMITTED
              AND s.deletedAt IS NULL
            """)
    int markDeletedByMediaId(
            @Param("mediaId") UUID mediaId,
            @Param("deletedAt") Instant deletedAt,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StorageObjectEntity s
            SET s.expiresAt = :newExpiresAt,
                s.updatedAt = :now
            WHERE s.uploadId = :uploadId
              AND s.status = com.mediamesh.ingestion.storage.persistence.StorageObjectStatus.PENDING
              AND s.expiresAt < :newExpiresAt
            """)
    int slideExpiresAt(
            @Param("uploadId") UUID uploadId,
            @Param("newExpiresAt") Instant newExpiresAt,
            @Param("now") Instant now);

    @Query("""
            SELECT s FROM StorageObjectEntity s
            WHERE s.status = com.mediamesh.ingestion.storage.persistence.StorageObjectStatus.PENDING
              AND s.expiresAt < :now
            ORDER BY s.expiresAt ASC
            """)
    List<StorageObjectEntity> findExpiredPending(@Param("now") Instant now, Pageable pageable);

    @Query("""
            SELECT s FROM StorageObjectEntity s
            WHERE s.status = com.mediamesh.ingestion.storage.persistence.StorageObjectStatus.COMMITTING
              AND s.updatedAt < :cutoff
            ORDER BY s.updatedAt ASC
            """)
    List<StorageObjectEntity> findStuckCommitting(@Param("cutoff") Instant cutoff, Pageable pageable);
}
