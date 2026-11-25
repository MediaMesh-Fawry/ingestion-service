package com.mediamesh.ingestion.features.cleanup;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for {@link CleanupWorker}.
 */
@ConfigurationProperties(prefix = "storage.cleanup")
public record CleanupProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30s") Duration pollInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("2m") Duration leaseDuration,
        @DefaultValue("5m") Duration stuckCommitAfter,
        @DefaultValue("1h") Duration maxBackoff) {}
