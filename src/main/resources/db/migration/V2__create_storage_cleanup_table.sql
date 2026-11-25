CREATE TABLE storage_cleanup (
    id                 BIGSERIAL PRIMARY KEY,
    kind               VARCHAR(16) NOT NULL
                           CHECK (kind IN ('ABORT_MP', 'DELETE_OBJECT', 'DELETE_PREFIX')),
    bucket             VARCHAR(128) NOT NULL,
    object_key         VARCHAR(512) NOT NULL,
    provider_upload_id VARCHAR(512),
    attempts           INT NOT NULL DEFAULT 0,
    run_after          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_storage_cleanup_due
    ON storage_cleanup (run_after);
