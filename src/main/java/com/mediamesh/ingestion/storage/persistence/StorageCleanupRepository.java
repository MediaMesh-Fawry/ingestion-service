package com.mediamesh.ingestion.storage.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link StorageCleanupEntity} supporting placeholder deletion
 * and multi-instance safe claiming via {@code FOR UPDATE SKIP LOCKED}.
 */
public interface StorageCleanupRepository extends JpaRepository<StorageCleanupEntity, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM StorageCleanupEntity c
            WHERE c.kind = com.mediamesh.ingestion.storage.persistence.CleanupKind.ABORT_MP
              AND c.bucket = :bucket
              AND c.objectKey = :objectKey
              AND c.providerUploadId IS NULL
            """)
    int deletePlaceholderAbort(
            @Param("bucket") String bucket, @Param("objectKey") String objectKey);

    @Query(
            value = """
                    SELECT * FROM storage_cleanup
                    WHERE run_after <= :now
                    ORDER BY run_after ASC
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    List<StorageCleanupEntity> claimDueTasks(@Param("now") Instant now, @Param("limit") int limit);

    @Query(
            value = """
                    UPDATE storage_cleanup
                    SET run_after = :leaseUntil
                    WHERE id IN (
                        SELECT id FROM storage_cleanup
                        WHERE run_after <= :now
                        ORDER BY run_after ASC
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                    )
                    RETURNING *
                    """,
            nativeQuery = true)
    List<StorageCleanupEntity> claimDueTasksWithLease(
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("limit") int limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StorageCleanupEntity c
            SET c.attempts = :attempts,
                c.runAfter = :nextRunAfter
            WHERE c.id = :id
            """)
    int rescheduleWithBackoff(
            @Param("id") Long id,
            @Param("attempts") int attempts,
            @Param("nextRunAfter") Instant nextRunAfter);
}
