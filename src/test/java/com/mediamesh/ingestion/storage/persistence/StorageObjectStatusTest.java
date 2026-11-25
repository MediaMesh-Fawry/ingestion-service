package com.mediamesh.ingestion.storage.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StorageObjectStatusTest {

    @Test
    void toOpenApiStatus_mapsEveryInternalState() {
        assertThat(StorageObjectStatus.ALLOCATING.toOpenApiStatus()).isEqualTo("UPLOADING");
        assertThat(StorageObjectStatus.PENDING.toOpenApiStatus()).isEqualTo("UPLOADING");
        assertThat(StorageObjectStatus.COMMITTING.toOpenApiStatus()).isEqualTo("PROCESSING");
        assertThat(StorageObjectStatus.COMMITTED.toOpenApiStatus()).isEqualTo("PROCESSING");
        assertThat(StorageObjectStatus.ABORTED.toOpenApiStatus()).isEqualTo("ABORTED");
        assertThat(StorageObjectStatus.EXPIRED.toOpenApiStatus()).isEqualTo("EXPIRED");
        assertThat(StorageObjectStatus.FAILED.toOpenApiStatus()).isEqualTo("FAILED");
        assertThat(StorageObjectStatus.DELETED.toOpenApiStatus()).isEqualTo("DELETED");
    }
}
