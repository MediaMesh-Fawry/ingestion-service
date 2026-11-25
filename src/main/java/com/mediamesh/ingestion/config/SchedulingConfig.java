package com.mediamesh.ingestion.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} execution unless {@code storage.cleanup.enabled=false}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "storage.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
