package com.mediamesh.ingestion.storage.events;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Logging stub for {@link EventPublisher} until the RabbitMQ transactional outbox is wired.
 */
@Slf4j
@Component
public class LoggingEventPublisher implements EventPublisher {

    @Override
    public void mediaUploaded(MediaUploadedEvent event) {
        log.info(
                "Event media.uploaded mediaId={} groupId={} libraryId={} userId={} sizeBytes={} contentType={}",
                event.mediaId(),
                event.groupId(),
                event.libraryId(),
                event.userId(),
                event.sizeBytes(),
                event.contentType());
    }

    @Override
    public void mediaDeleted(MediaDeletedEvent event) {
        log.info(
                "Event media.deleted mediaId={} groupId={} deletedAt={}",
                event.mediaId(),
                event.groupId(),
                event.deletedAt());
    }
}
