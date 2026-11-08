-- Withdraw legacy public access without deleting videos, uploads, originals or HLS.
-- Preserve existing UNLISTED shares and their generations.
ALTER TABLE videos ALTER COLUMN visibility SET DEFAULT 'PRIVATE';
UPDATE videos
SET visibility = 'PRIVATE', is_published = FALSE, published_at = NULL,
    share_version = gen_random_uuid(), updated_at = NOW()
WHERE visibility = 'PUBLIC';
DROP INDEX IF EXISTS idx_video_public_discovery;

