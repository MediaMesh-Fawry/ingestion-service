package com.mediamesh.ingestion.storage.persistence;

/**
 * Asynchronous S3 cleanup task types persisted in {@code storage_cleanup}.
 */
public enum CleanupKind {
    ABORT_MP,
    DELETE_OBJECT,
    DELETE_PREFIX
}
