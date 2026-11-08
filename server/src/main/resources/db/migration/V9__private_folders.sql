CREATE TABLE video_folders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(64) NOT NULL,
    name_key VARCHAR(128) NOT NULL,
    UNIQUE(owner_id, name_key),
    UNIQUE(owner_id, id)
);
ALTER TABLE videos ADD COLUMN folder_id UUID;
-- Folder removal explicitly unfiles videos in the same transaction; never cascades video deletion.
ALTER TABLE videos ADD CONSTRAINT fk_video_owned_folder FOREIGN KEY (uploader_id, folder_id) REFERENCES video_folders(owner_id, id);
CREATE INDEX idx_video_folder_page ON videos(uploader_id, folder_id, created_at DESC, id DESC);
