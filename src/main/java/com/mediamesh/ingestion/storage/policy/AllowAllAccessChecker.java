package com.mediamesh.ingestion.storage.policy;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Permissive default {@link AccessChecker} stub used until ACL/membership checks are integrated.
 */
@Component
public class AllowAllAccessChecker implements AccessChecker {

    @Override
    public boolean canUpload(UUID userId, UUID groupId, UUID libraryId) {
        return true;
    }

    @Override
    public boolean canDelete(UUID userId, UUID mediaId) {
        return true;
    }
}
