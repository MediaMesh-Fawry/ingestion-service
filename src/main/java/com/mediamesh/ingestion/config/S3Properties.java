package com.mediamesh.ingestion.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * Configuration properties for S3-compatible object storage.
 */
@ConfigurationProperties(prefix = "storage.s3")
public record S3Properties(
        String endpoint,
        String publicEndpoint,
        @DefaultValue("us-east-1") String region,
        String accessKey,
        String secretKey,
        @DefaultValue("mediamesh-raw") String rawBucket,
        @DefaultValue("mediamesh-processed") String processedBucket,
        @DefaultValue("true") boolean pathStyleAccess,
        @DefaultValue("false") boolean fullObjectChecksumEnabled,
        @DefaultValue("20m") Duration presignTtl,
        @DefaultValue("24h") Duration uploadTtl,
        @DefaultValue("7d") Duration maxUploadLifetime,
        @DefaultValue("5497558138880") long maxObjectSizeBytes,
        @DefaultValue("65536") int headerInspectBytes) {

    /**
     * Returns the endpoint used for signing client-facing presigned URLs,
     * falling back to the internal endpoint when no separate public endpoint is configured.
     */
    public String effectivePresignerEndpoint() {
        return StringUtils.hasText(publicEndpoint) ? publicEndpoint : endpoint;
    }
}
