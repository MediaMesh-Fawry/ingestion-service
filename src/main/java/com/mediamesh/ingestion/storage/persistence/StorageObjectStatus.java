package com.mediamesh.ingestion.storage.persistence;

/**
 * Internal state-machine states for {@link StorageObjectEntity}.
 */
public enum StorageObjectStatus {
    ALLOCATING,
    PENDING,
    COMMITTING,
    COMMITTED,
    ABORTED,
    EXPIRED,
    FAILED,
    DELETED;

    /**
     * Maps internal persistence status to the external OpenAPI status string.
     */
    public String toOpenApiStatus() {
        return switch (this) {
            case ALLOCATING, PENDING -> "UPLOADING";
            case COMMITTING, COMMITTED -> "PROCESSING";
            case ABORTED -> "ABORTED";
            case EXPIRED -> "EXPIRED";
            case FAILED -> "FAILED";
            case DELETED -> "DELETED";
        };
    }
}
