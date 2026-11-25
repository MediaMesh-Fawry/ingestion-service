package com.mediamesh.ingestion.security;

import com.mediamesh.ingestion.common.exception.ApiException;
import java.util.UUID;

/**
 * Parses API Gateway-injected identity headers.
 */
public final class IdentityHeaderResolver {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private IdentityHeaderResolver() {}

    /**
     * Parses a required {@code X-User-Id} header into a {@link UUID}, failing closed with 401.
     */
    public static UUID parseUserId(String rawUserId) {
        if (rawUserId == null || rawUserId.isBlank()) {
            throw ApiException.unauthorized("Missing required authentication header: " + USER_ID_HEADER);
        }
        try {
            return UUID.fromString(rawUserId.trim());
        } catch (IllegalArgumentException ex) {
            throw ApiException.unauthorized("Invalid " + USER_ID_HEADER + " header format");
        }
    }
}
