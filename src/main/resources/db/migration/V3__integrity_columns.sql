ALTER TABLE storage_objects
    ADD COLUMN expected_checksum        VARCHAR(16),
    ADD COLUMN checksum_mismatch_count  INT NOT NULL DEFAULT 0;
