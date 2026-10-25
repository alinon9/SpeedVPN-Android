-- Migration 003: Durable speed plan usage history sessions
-- Tracks actual forwarded VPN payload bytes (download & upload) per applied speed plan segment.
-- Bumps speedvpn_usage.db from version 4 to version 5.

CREATE TABLE IF NOT EXISTS speed_plan_usage_session (
    session_id TEXT PRIMARY KEY NOT NULL,
    vpn_generation INTEGER NOT NULL,
    segment_index INTEGER NOT NULL DEFAULT 1,
    process_token TEXT NOT NULL,
    started_at_millis INTEGER NOT NULL,
    ended_at_millis INTEGER,
    last_checkpoint_millis INTEGER NOT NULL,
    duration_millis INTEGER NOT NULL DEFAULT 0 CHECK (duration_millis >= 0),
    plan_option_category TEXT NOT NULL,
    plan_option_label TEXT NOT NULL,
    download_limit_kbps INTEGER,
    upload_limit_kbps INTEGER,
    download_limit_unit_label TEXT NOT NULL,
    upload_limit_unit_label TEXT NOT NULL,
    download_bytes INTEGER NOT NULL DEFAULT 0 CHECK (download_bytes >= 0),
    upload_bytes INTEGER NOT NULL DEFAULT 0 CHECK (upload_bytes >= 0),
    total_bytes INTEGER NOT NULL DEFAULT 0 CHECK (total_bytes >= 0),
    avg_download_bps INTEGER NOT NULL DEFAULT 0 CHECK (avg_download_bps >= 0),
    avg_upload_bps INTEGER NOT NULL DEFAULT 0 CHECK (avg_upload_bps >= 0),
    peak_download_bps INTEGER NOT NULL DEFAULT 0 CHECK (peak_download_bps >= 0),
    peak_upload_bps INTEGER NOT NULL DEFAULT 0 CHECK (peak_upload_bps >= 0),
    status TEXT NOT NULL CHECK (status IN ('ONGOING', 'COMPLETED', 'ABNORMAL_END')),
    close_reason TEXT NOT NULL DEFAULT 'ACTIVE',
    limit_exceeded INTEGER NOT NULL DEFAULT 0 CHECK (limit_exceeded IN (0, 1)),
    max_exceedance_pct REAL NOT NULL DEFAULT 0.0 CHECK (max_exceedance_pct >= 0.0),
    exceedance_duration_millis INTEGER NOT NULL DEFAULT 0 CHECK (exceedance_duration_millis >= 0)
);

CREATE INDEX IF NOT EXISTS idx_speed_plan_session_started
    ON speed_plan_usage_session(started_at_millis DESC);

CREATE INDEX IF NOT EXISTS idx_speed_plan_session_status
    ON speed_plan_usage_session(status, process_token);

CREATE INDEX IF NOT EXISTS idx_speed_plan_session_category
    ON speed_plan_usage_session(plan_option_category);
