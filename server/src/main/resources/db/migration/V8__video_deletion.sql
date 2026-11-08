-- Tombstones deny access immediately; cleanup is durable and never tied to an HTTP request.
ALTER TABLE videos ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE videos ADD COLUMN storage_deleted_at TIMESTAMPTZ;
ALTER TABLE videos ADD COLUMN deletion_cleanup_token UUID;
ALTER TABLE videos ADD COLUMN deletion_cleanup_lease_until TIMESTAMPTZ;
ALTER TABLE videos ADD COLUMN deletion_cleanup_next_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE videos ADD COLUMN deletion_cleanup_attempts INT NOT NULL DEFAULT 0;
CREATE INDEX idx_video_deletion_due ON videos(deletion_cleanup_next_at) WHERE deleted_at IS NOT NULL AND storage_deleted_at IS NULL;
