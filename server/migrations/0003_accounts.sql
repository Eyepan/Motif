-- Account management: a display name, and a row per signed-in device so
-- Settings can list devices and sign them out. Additive only.

ALTER TABLE users ADD COLUMN display_name TEXT;

-- One row per sign-in (a refresh token family). Families created before this
-- migration have no row; they are listed with what refresh_tokens knows.
CREATE TABLE sessions (
    family_id    UUID PRIMARY KEY,
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_name  TEXT,                                 -- as the app reported it, e.g. "Pan's iPhone"
    platform     TEXT,                                 -- ios | ipados | macos | android | other
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT now()    -- last sign-in or refresh
);
CREATE INDEX sessions_user ON sessions(user_id);
