package com.mediamesh.ingestion.storage.policy;

import java.util.UUID;

/**
 * Extension point for group/library authorization checks.
 */
public interface AccessChecker {

    boolean canUpload(UUID userId, UUID groupId, UUID libraryId);

    boolean canDelete(UUID userId, UUID mediaId);
}
