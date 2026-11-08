-- Additive migration: V1 checksum and existing upload/account records are preserved.
ALTER TABLE processing_jobs ADD COLUMN lease_until TIMESTAMPTZ;
ALTER TABLE processing_jobs ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE videos ADD COLUMN processed_bucket TEXT;
ALTER TABLE videos ADD COLUMN master_playlist_key TEXT;
ALTER TABLE video_renditions ADD COLUMN playlist_object_key TEXT;

CREATE TABLE processing_attempts (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES processing_jobs(id) ON DELETE CASCADE,
    bucket TEXT NOT NULL,
    object_prefix TEXT NOT NULL UNIQUE,
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    cleanup_after TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_processing_due ON processing_jobs(status, next_attempt_at, lease_until);
CREATE INDEX idx_processing_cleanup ON processing_attempts(state, cleanup_after);
