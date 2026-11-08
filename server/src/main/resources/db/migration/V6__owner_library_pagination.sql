-- Stable owner-only keyset pagination, including uploads with identical creation dates.
CREATE INDEX IF NOT EXISTS idx_videos_owner_created_id ON videos(uploader_id, created_at DESC, id DESC);
