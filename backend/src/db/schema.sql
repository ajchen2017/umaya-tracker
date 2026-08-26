CREATE TABLE IF NOT EXISTS users (
  id            SERIAL PRIMARY KEY,
  email         TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  display_name  TEXT NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Share token lives on the user, not the hike: a family's link should keep
-- working across every trip the hiker records, not just the one it was issued
-- for. Nullable at first so it can be added to an existing table, then
-- backfilled and locked down below.
ALTER TABLE users ADD COLUMN IF NOT EXISTS share_token TEXT UNIQUE;
UPDATE users SET share_token = substr(md5(random()::text || clock_timestamp()::text || id::text), 1, 16)
  WHERE share_token IS NULL;
ALTER TABLE users ALTER COLUMN share_token SET NOT NULL;

CREATE TABLE IF NOT EXISTS hikes (
  id               SERIAL PRIMARY KEY,
  user_id          INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name             TEXT NOT NULL,
  status           TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'ended')),
  started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at         TIMESTAMPTZ,
  planned_route    TEXT, -- raw GPX or KML file content, as uploaded
  alert_config     JSONB -- thresholds for the alert-level heuristic; null = use defaults, see src/lib/alertLevel.js
);

ALTER TABLE hikes ADD COLUMN IF NOT EXISTS alert_config JSONB;
-- Superseded by users.share_token (see above) — a hike-level token meant every
-- new trip broke the family's saved link.
ALTER TABLE hikes DROP COLUMN IF EXISTS share_token;

CREATE TABLE IF NOT EXISTS track_points (
  id           SERIAL PRIMARY KEY,
  hike_id      INTEGER NOT NULL REFERENCES hikes(id) ON DELETE CASCADE,
  client_id    TEXT NOT NULL,
  lat          DOUBLE PRECISION NOT NULL,
  lng          DOUBLE PRECISION NOT NULL,
  altitude     DOUBLE PRECISION,
  accuracy     DOUBLE PRECISION,
  marker_type  TEXT NOT NULL DEFAULT 'normal' CHECK (marker_type IN ('normal', 'safe', 'sos', 'camping')),
  battery_pct  SMALLINT CHECK (battery_pct BETWEEN 0 AND 100),
  recorded_at  TIMESTAMPTZ NOT NULL,
  uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (hike_id, client_id)
);

ALTER TABLE track_points ADD COLUMN IF NOT EXISTS battery_pct SMALLINT CHECK (battery_pct BETWEEN 0 AND 100);

-- 'camping' added after initial launch — widen the constraint on databases that
-- were created before it existed (constraint name is Postgres's default for an
-- inline CHECK on this column).
ALTER TABLE track_points DROP CONSTRAINT IF EXISTS track_points_marker_type_check;
ALTER TABLE track_points ADD CONSTRAINT track_points_marker_type_check
  CHECK (marker_type IN ('normal', 'safe', 'sos', 'camping'));

CREATE INDEX IF NOT EXISTS idx_track_points_hike_recorded
  ON track_points (hike_id, recorded_at DESC);

-- Safe to re-run against a database created before this column existed.
ALTER TABLE hikes ADD COLUMN IF NOT EXISTS planned_route TEXT;

-- Mountain-area mobile signal reference points, pooled from multiple government
-- sources. Reference data, not tied to any one hike. Each source owns its own
-- rows (source column) and is refreshed independently — see src/admin/updateSignalPoints.js.
CREATE TABLE IF NOT EXISTS signal_points (
  id            SERIAL PRIMARY KEY,
  source        TEXT NOT NULL DEFAULT 'forestry', -- forestry | sheipa | ysnp | taroko
  seq           INTEGER,
  trail_name    TEXT,
  branch        TEXT,
  location_desc TEXT,
  county        TEXT,
  lat           DOUBLE PRECISION NOT NULL,
  lng           DOUBLE PRECISION NOT NULL,
  cht           BOOLEAN NOT NULL DEFAULT false, -- 中華電信
  fet           BOOLEAN NOT NULL DEFAULT false, -- 遠傳電信
  twm           BOOLEAN NOT NULL DEFAULT false, -- 台灣大哥大
  other         BOOLEAN NOT NULL DEFAULT false, -- confirmed reachable, carrier not identified by the source
  remark        TEXT
);

ALTER TABLE signal_points ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'forestry';
ALTER TABLE signal_points ADD COLUMN IF NOT EXISTS other BOOLEAN NOT NULL DEFAULT false;

-- Small key/value store, e.g. tracking the source filename last imported so a
-- re-check can skip re-downloading when nothing has changed.
CREATE TABLE IF NOT EXISTS app_metadata (
  key   TEXT PRIMARY KEY,
  value TEXT
);
