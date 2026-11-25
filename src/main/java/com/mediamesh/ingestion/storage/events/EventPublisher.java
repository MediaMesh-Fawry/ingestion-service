package com.mediamesh.ingestion.storage.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Extension point for publishing domain events after verified storage state changes.
 */
public interface EventPublisher {

    void mediaUploaded(MediaUploadedEvent event);

    void mediaDeleted(MediaDeletedEvent event);

    record MediaUploadedEvent(
            UUID mediaId,
            UUID groupId,
            UUID libraryId,
            UUID userId,
            String bucket,
            String objectKey,
            String contentType,
            long sizeBytes,
            String checksum) {}

    record MediaDeletedEvent(UUID mediaId, UUID groupId, Instant deletedAt) {}
}
