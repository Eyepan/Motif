-- Listening history the user deleted (docs/analytics.md, Privacy controls).
-- Events of the listening types with at_ms in [from_ms, to_ms) are removed,
-- and later uploads of such events are dropped, so a device that was offline
-- cannot bring them back. Each row is also a `history_deleted` event in the
-- log with the same id, which tells the other devices to delete them locally.
CREATE TABLE history_deletions (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    from_ms     BIGINT NOT NULL,
    to_ms       BIGINT NOT NULL,
    deleted_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX history_deletions_user ON history_deletions(user_id);
