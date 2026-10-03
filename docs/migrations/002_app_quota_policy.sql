-- SpeedVPN Usage DB v1 -> v2
-- PR1 only: schema/model migration. No quota enforcement is performed here.
--
-- Design decisions:
-- 1) app_policy.package_name is already PRIMARY KEY, so it is a valid FK parent key.
-- 2) UsageDbHelper enables SQLite foreign_keys in onConfigure(), so ON DELETE CASCADE is enforced.
-- 3) app_quota_policy intentionally does NOT duplicate uid. The authoritative UID remains
--    app_policy.uid; future UID-sensitive code must JOIN app_policy by package_name.
-- 4) daily_limit_bytes <= 0 historically means "no limit" in the existing UI/repository path.
--    Therefore migration deliberately does NOT create a DAILY policy for NULL or zero.
-- 5) updated_at has a SQLite default as a defensive guard; normal repository writes still set it explicitly.

CREATE TABLE IF NOT EXISTS app_quota_policy (
    package_name TEXT NOT NULL,
    quota_type TEXT NOT NULL
        CHECK (quota_type IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    limit_bytes INTEGER NOT NULL
        CHECK (limit_bytes > 0),
    period_start_millis INTEGER NOT NULL,
    period_end_millis INTEGER NOT NULL,
    used_bytes INTEGER NOT NULL DEFAULT 0
        CHECK (used_bytes >= 0),
    reset_behavior TEXT NOT NULL DEFAULT 'AUTO_RESET'
        CHECK (reset_behavior IN ('AUTO_RESET', 'BLOCK_UNTIL_RESET')),
    updated_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000),
    PRIMARY KEY (package_name, quota_type),
    FOREIGN KEY (package_name)
        REFERENCES app_policy(package_name)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_app_quota_type
    ON app_quota_policy(package_name, quota_type);

-- The Kotlin migration supplies:
--   ?1 = current DAILY period_start_millis
--   ?2 = current DAILY period_end_millis
--   ?3 = migration timestamp (System.currentTimeMillis())
INSERT OR IGNORE INTO app_quota_policy
(package_name, quota_type, limit_bytes,
 period_start_millis, period_end_millis, used_bytes, reset_behavior, updated_at)
SELECT
    package_name,
    'DAILY',
    daily_limit_bytes,
    ?1,
    ?2,
    0,
    'AUTO_RESET',
    ?3
FROM app_policy
WHERE daily_limit_bytes IS NOT NULL
  AND daily_limit_bytes > 0;
