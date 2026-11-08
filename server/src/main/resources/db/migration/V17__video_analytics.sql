CREATE TABLE video_analytics_sessions (
    id UUID PRIMARY KEY,
    video_id UUID NOT NULL REFERENCES videos(id),
    creator_id UUID NOT NULL REFERENCES users(id),
    viewer_hash VARCHAR(64) NOT NULL,
    report_day DATE NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    started BOOLEAN NOT NULL DEFAULT FALSE,
    qualified BOOLEAN NOT NULL DEFAULT FALSE,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    reported_ms BIGINT NOT NULL DEFAULT 0,
    watched_ms BIGINT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL CHECK (duration_ms > 0),
    CHECK (reported_ms >= 0 AND watched_ms >= 0)
);
CREATE INDEX analytics_creator_day ON video_analytics_sessions(creator_id, report_day);
CREATE INDEX analytics_video_day ON video_analytics_sessions(video_id, report_day);
CREATE INDEX analytics_retention ON video_analytics_sessions(opened_at);
