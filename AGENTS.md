# AGENTS.md — MediaMesh Ingestion Service

Unified **Ingestion & Storage Service** for **MediaMesh** (Java 21, Spring Boot 4.2.0, Maven, PostgreSQL + Flyway, RabbitMQ, Redis, S3/MinIO).

## Workflow & Commands

- **Commands:** Prefer `Taskfile.yml` (`task test`, `task build`, `task lint`, `task dev`, `task infra`, `task run`, `task docker`) or `./mvnw test -B` | `./mvnw package -DskipTests -B` | `docker compose up -d`
- **Git:** Always use a feature branch (`feature/<name>`) for new features; never commit directly to `main`.

## Boundaries

- **Owns:** Upload orchestration, S3-compatible object storage abstraction, per-user storage quotas, media ID minting, and storage cleanup/reconciliation.
- **Does Not Own:** Proxying or locally storing media bytes, CORS, JWT parsing, metadata/search, transcoding, streaming, or billing.

## High-Level Rules

1. **Direct-to-Storage & Private by Default:** Keep buckets private and issue short-lived presigned URLs so clients transfer media directly to object storage. Never expose storage credentials or raw SDK errors, and never use user filenames as object keys.
2. **State & Source-of-Truth Separation:** The database owns upload state, ownership, and quotas; object storage owns binary objects and uploaded-part state. Persist upload intent before issuing URLs, keep provider IDs separate from application IDs, and enforce a strict upload state machine.
3. **Atomic Per-User Quotas:** Reserve quota atomically at upload init, commit on verified completion, and release on failure, abort, or expiry.
4. **Verify Before Commit:** Never trust client-reported sizes, manifests, MIME types, or multipart ETags. Reconcile parts, verify actual object size and content signature, and commit only after verification passes.
5. **Resumability, Idempotency & Consistency:** Support resumable uploads, idempotent initiation/completion, and crash reconciliation between object storage and the database. Use a transactional outbox and idempotent consumers on RabbitMQ.
6. **Immutability & Cleanup:** Treat originals as write-once. Reclaim storage and purge raw/processed objects on deletion (handling late transcoding outputs), abort abandoned uploads, and reconcile orphaned objects safely.
7. **Security, Observability & Testing:** Trust API Gateway identity headers and enforce strict per-user authorization (fail closed). Use Flyway for schema changes, include correlation/entity IDs in logs without ever logging presigned URLs or secrets, and test failure/concurrency edge cases.
