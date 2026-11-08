-- Support bounded per-reporter checks without exposing reports to other users.
CREATE INDEX IF NOT EXISTS idx_reports_reporter_created ON reports(reporter_id, created_at);
CREATE INDEX IF NOT EXISTS idx_reports_reporter_video_status ON reports(reporter_id, video_id, status);
