package com.mediamesh.ingestion.storage.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * JPA entity for {@code storage_objects}, representing upload intent,
 * ownership,
 * object location, and verified storage state.
 */
@Entity
@Table(name = "storage_objects")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageObjectEntity {

    @Id
    @Column(name = "upload_id", nullable = false, updatable = false)
    private UUID uploadId;

    @Column(name = "media_id", nullable = false, unique = true, updatable = false)
    private UUID mediaId;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @Column(name = "library_id", nullable = false, updatable = false)
    private UUID libraryId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "bucket", nullable = false, length = 128)
    private String bucket;

    @Column(name = "object_key", nullable = false, unique = true, length = 512)
    private String objectKey;

    @Column(name = "provider_upload_id", length = 512)
    private String providerUploadId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StorageObjectStatus status;

    @Column(name = "declared_size", nullable = false)
    private long declaredSize;

    @Column(name = "actual_size")
    private Long actualSize;

    @Column(name = "part_size", nullable = false)
    private long partSize;

    @Column(name = "total_parts", nullable = false)
    private int totalParts;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "etag", length = 128)
    private String etag;

    @Column(name = "checksum", length = 128)
    private String checksum;

    @Column(name = "checksum_algorithm", length = 32)
    private String checksumAlgorithm;

    @Column(name = "expected_checksum", length = 128)
    private String expectedChecksum;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
