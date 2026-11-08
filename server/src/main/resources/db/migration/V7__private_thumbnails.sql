-- Private preview metadata; old videos remain playable while backfill is pending.
ALTER TABLE videos ADD COLUMN thumbnail_object_key TEXT;
ALTER TABLE videos ADD COLUMN thumbnail_worker_id UUID;
ALTER TABLE videos ADD COLUMN thumbnail_lease_until TIMESTAMPTZ;
ALTER TABLE videos ADD COLUMN thumbnail_retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE videos ADD COLUMN thumbnail_next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
CREATE INDEX idx_video_thumbnail_pending ON videos(thumbnail_next_attempt_at) WHERE publication_state='PROCESSED' AND thumbnail_object_key IS NULL;
