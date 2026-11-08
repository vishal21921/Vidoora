CREATE TABLE private_downloads (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), period_hash VARCHAR(64) NOT NULL,
 video_id UUID NOT NULL REFERENCES videos(id), manifest TEXT NOT NULL, charged BOOLEAN NOT NULL DEFAULT FALSE,
 state VARCHAR(16) NOT NULL, reserved_until TIMESTAMP WITH TIME ZONE NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(user_id,period_hash,video_id)
);
CREATE INDEX idx_private_download_user ON private_downloads(user_id,period_hash);
CREATE TABLE private_download_assets (
 download_id UUID NOT NULL REFERENCES private_downloads(id), path VARCHAR(80) NOT NULL, sha256 VARCHAR(64) NOT NULL,
 PRIMARY KEY(download_id,path)
);
CREATE TABLE private_download_clients (
 download_id UUID NOT NULL REFERENCES private_downloads(id), client_id UUID NOT NULL, state VARCHAR(16) NOT NULL,
 reserved_until TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY(download_id,client_id)
);
