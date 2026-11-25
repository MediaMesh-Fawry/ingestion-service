package com.mediamesh.ingestion.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for Redis-backed HTTP {@code Idempotency-Key} deduplication.
 */
@ConfigurationProperties(prefix = "storage.idempotency")
public record IdempotencyProperties(
        @DefaultValue("24h") Duration ttl,
        @DefaultValue("60s") Duration lockTtl,
        @DefaultValue("255") int maxKeyLength) {}
