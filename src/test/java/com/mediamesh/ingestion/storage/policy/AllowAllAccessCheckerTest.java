package com.mediamesh.ingestion.storage.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AllowAllAccessCheckerTest {

    private final AllowAllAccessChecker checker = new AllowAllAccessChecker();

    @Test
    void allowsUploadAndDeleteForAnyIds() {
        UUID userId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        assertThat(checker.canUpload(userId, groupId, libraryId)).isTrue();
        assertThat(checker.canDelete(userId, mediaId)).isTrue();
    }
}
