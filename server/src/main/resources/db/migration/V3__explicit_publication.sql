-- Existing uploads remain unpublished; no content or ownership is changed.
ALTER TABLE videos ADD COLUMN is_published BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE videos ADD COLUMN share_version UUID NOT NULL DEFAULT gen_random_uuid();
CREATE INDEX idx_video_public_discovery ON videos (published_at DESC, id DESC)
    WHERE is_published = TRUE AND visibility = 'PUBLIC' AND publication_state = 'PROCESSED';
