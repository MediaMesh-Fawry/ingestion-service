CREATE TABLE storage_objects (
    upload_id          UUID PRIMARY KEY,
    media_id           UUID NOT NULL UNIQUE,
    group_id           UUID NOT NULL,
    library_id         UUID NOT NULL,
    user_id            UUID NOT NULL,
    file_name          VARCHAR(255) NOT NULL,
    title              VARCHAR(255),
    bucket             VARCHAR(128) NOT NULL,
    object_key         VARCHAR(512) NOT NULL UNIQUE,
    provider_upload_id VARCHAR(512),
    status             VARCHAR(20) NOT NULL
                           CHECK (status IN (
                               'ALLOCATING', 'PENDING', 'COMMITTING', 'COMMITTED',
                               'ABORTED', 'EXPIRED', 'FAILED', 'DELETED'
                           )),
    declared_size      BIGINT NOT NULL CHECK (declared_size > 0),
    actual_size        BIGINT,
    part_size          BIGINT NOT NULL CHECK (part_size > 0),
    total_parts        INT NOT NULL CHECK (total_parts BETWEEN 1 AND 10000),
    content_type       VARCHAR(128) NOT NULL,
    etag               VARCHAR(128),
    checksum           VARCHAR(128),
    checksum_algorithm VARCHAR(32),
    expected_checksum  VARCHAR(128),
    expires_at         TIMESTAMPTZ NOT NULL,
    deleted_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_storage_objects_sweep
    ON storage_objects (status, expires_at);

CREATE INDEX idx_storage_objects_user_active
    ON storage_objects (user_id, status);
