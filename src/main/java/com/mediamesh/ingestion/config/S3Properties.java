package com.mediamesh.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Configuration properties for S3-compatible object storage.
 */
@ConfigurationProperties(prefix = "storage.s3")
public record S3Properties(
        String endpoint,
        String publicEndpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket,
        boolean pathStyleAccess) {

    /**
     * Returns the endpoint used for signing client-facing presigned URLs,
     * falling back to the internal endpoint when no separate public endpoint is
     * configured.
     */
    public String effectivePresignerEndpoint() {
        return StringUtils.hasText(publicEndpoint) ? publicEndpoint : endpoint;
    }
}
