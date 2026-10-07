-- Motif sync service, initial schema.
-- Migrations must stay additive (expand, then contract in a later release):
-- the running deployment and the new one share the database during a rollout.

CREATE TABLE users (
    id          UUID PRIMARY KEY,                      -- UUIDv7
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per sign-in method. A user can link Apple and Google to one account.
CREATE TABLE identities (
    provider    TEXT NOT NULL,                         -- apple | google | dev
    subject     TEXT NOT NULL,                         -- the provider's stable user id ("sub")
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email       TEXT,                                  -- as last reported by the provider; may be a relay address
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, subject)
);
CREATE INDEX identities_user ON identities(user_id);

-- Rotating refresh tokens. Only a SHA-256 of the token is stored. Every token
-- from one sign-in shares a family_id; presenting a token that was already
-- rotated revokes the whole family (it was probably stolen).
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY,
    family_id   UUID NOT NULL,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  BYTEA NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX refresh_tokens_family ON refresh_tokens(family_id);
CREATE INDEX refresh_tokens_user ON refresh_tokens(user_id);

-- The listening history log (docs/analytics.md), merged across a user's devices.
-- Append-only: rows are inserted, never updated. The envelope mirrors
-- schemas/history.sql on the device; payload is the per-type JSON.
CREATE TABLE events (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    id          UUID NOT NULL,                         -- UUIDv7 minted on the device
    -- Server arrival order, used as the pull cursor. Uploads for one user are
    -- serialized with an advisory lock, so a user's seq values become visible
    -- in increasing order and a cursor never skips a late commit.
    seq         BIGINT GENERATED ALWAYS AS IDENTITY,
    type        TEXT NOT NULL,
    v           INTEGER NOT NULL,
    at_ms       BIGINT NOT NULL,
    tz_min      INTEGER NOT NULL,
    device_id   TEXT NOT NULL,
    session_id  TEXT,
    track_id    TEXT,
    track_key   TEXT,
    payload     JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, id)
);
CREATE UNIQUE INDEX events_user_seq ON events(user_id, seq);
CREATE INDEX events_user_at ON events(user_id, at_ms);
