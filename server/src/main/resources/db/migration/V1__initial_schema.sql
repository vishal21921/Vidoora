-- 1. Users Table
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(32) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    avatar_url TEXT,
    bio VARCHAR(255),
    is_suspended BOOLEAN NOT NULL DEFAULT FALSE,
    role VARCHAR(16) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. Videos Table
CREATE TABLE IF NOT EXISTS videos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    uploader_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(128) NOT NULL,
    description TEXT,
    duration_seconds INT NOT NULL DEFAULT 0,
    publication_state VARCHAR(24) NOT NULL DEFAULT 'CREATED',
    visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    raw_object_key TEXT,
    master_playlist_url TEXT,
    thumbnail_url TEXT,
    views_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 3. Upload Sessions Table
CREATE TABLE IF NOT EXISTS upload_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id UUID NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
    uploader_id UUID NOT NULL REFERENCES users(id),
    upload_id_provider TEXT NOT NULL,
    object_key TEXT NOT NULL,
    total_bytes BIGINT NOT NULL,
    chunk_size_bytes INT NOT NULL DEFAULT 5242880,
    completed_parts JSONB DEFAULT '[]'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 4. Processing Jobs Table
CREATE TABLE IF NOT EXISTS processing_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id UUID NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 3,
    error_message TEXT,
    worker_id VARCHAR(64),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 5. Video Renditions Table
CREATE TABLE IF NOT EXISTS video_renditions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id UUID NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
    resolution VARCHAR(12) NOT NULL,
    bitrate_bps INT NOT NULL,
    codec VARCHAR(32) NOT NULL DEFAULT 'h264',
    playlist_url TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 6. Playback Sessions & Raw Heartbeats
CREATE TABLE IF NOT EXISTS playback_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id UUID NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    device_fingerprint VARCHAR(64) NOT NULL,
    ip_address INET NOT NULL,
    user_agent TEXT,
    watch_time_seconds INT NOT NULL DEFAULT 0,
    is_validated_view BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 7. Hourly View Events Rollup
CREATE TABLE IF NOT EXISTS view_events_hourly (
    video_id UUID NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
    hourly_timestamp TIMESTAMPTZ NOT NULL,
    raw_play_requests INT NOT NULL DEFAULT 0,
    validated_views INT NOT NULL DEFAULT 0,
    total_watch_seconds BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (video_id, hourly_timestamp)
);

-- 8. Ad Impressions Table
CREATE TABLE IF NOT EXISTS ad_impressions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id UUID NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
    viewer_id UUID REFERENCES users(id),
    ad_unit_id VARCHAR(64) NOT NULL,
    format VARCHAR(24) NOT NULL,
    impression_token VARCHAR(128) NOT NULL UNIQUE,
    estimated_revenue_usd NUMERIC(12, 6) DEFAULT 0.000000,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 9. Creator Earnings Ledger Table
CREATE TABLE IF NOT EXISTS creator_earnings_ledger (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    creator_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    video_id UUID REFERENCES videos(id) ON DELETE SET NULL,
    entry_type VARCHAR(24) NOT NULL,
    amount_usd NUMERIC(12, 4) NOT NULL,
    description TEXT,
    reference_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 10. Content Reports Table
CREATE TABLE IF NOT EXISTS reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL REFERENCES users(id),
    video_id UUID REFERENCES videos(id) ON DELETE CASCADE,
    target_user_id UUID REFERENCES users(id),
    reason VARCHAR(32) NOT NULL,
    details TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 11. Moderation Actions Log
CREATE TABLE IF NOT EXISTS moderation_actions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    moderator_id UUID NOT NULL REFERENCES users(id),
    target_type VARCHAR(16) NOT NULL,
    target_id UUID NOT NULL,
    action VARCHAR(24) NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 12. User Blocks Table
CREATE TABLE IF NOT EXISTS user_blocks (
    blocker_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (blocker_id, blocked_id)
);

-- 13. Idempotency Records
CREATE TABLE IF NOT EXISTS idempotency_records (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    request_path VARCHAR(128) NOT NULL,
    response_code INT NOT NULL,
    response_body JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Essential Performance Indexes
CREATE INDEX IF NOT EXISTS idx_videos_feed ON videos (publication_state, visibility, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_videos_uploader ON videos (uploader_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_jobs_queue ON processing_jobs (status, created_at ASC) WHERE status = 'QUEUED';
CREATE INDEX IF NOT EXISTS idx_playback_session_fraud ON playback_sessions (video_id, device_fingerprint, created_at);
CREATE INDEX IF NOT EXISTS idx_ledger_creator ON creator_earnings_ledger (creator_id, created_at DESC);
