ALTER TABLE tts_jobs ADD COLUMN stage TEXT NOT NULL DEFAULT '';
ALTER TABLE tts_jobs ADD COLUMN diagnostics_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE tts_jobs ADD COLUMN cancel_requested INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tts_jobs ADD COLUMN last_activity_at TEXT;

CREATE INDEX idx_tts_jobs_project_active
    ON tts_jobs(project_id, status, cancel_requested);
