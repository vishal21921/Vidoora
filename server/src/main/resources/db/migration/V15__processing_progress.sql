ALTER TABLE processing_jobs ADD COLUMN progress_percent INTEGER NOT NULL DEFAULT 0 CHECK(progress_percent BETWEEN 0 AND 100);
