package com.mediamesh.ingestion.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables Spring Data JPA auditing for {@code @CreatedDate} and {@code @LastModifiedDate}.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {}
