package com.mediamesh.ingestion.storage.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for {@code storage_cleanup}, holding outbox-style deferred S3 cleanup tasks.
 */
@Entity
@Table(name = "storage_cleanup")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageCleanupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private CleanupKind kind;

    @Column(name = "bucket", nullable = false, length = 128)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "provider_upload_id", length = 512)
    private String providerUploadId;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "run_after", nullable = false)
    private Instant runAfter;

    @PrePersist
    void onCreate() {
        if (runAfter == null) {
            runAfter = Instant.now();
        }
    }
}
