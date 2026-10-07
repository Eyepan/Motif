-- Username and password sign-in. One credential per user; Apple and Google
-- sign-ins (identities) can be linked to the same user later.
CREATE TABLE password_credentials (
    user_id         UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    username        TEXT NOT NULL UNIQUE,              -- lowercased, see auth/password.rs
    password_hash   TEXT NOT NULL,                     -- Argon2id PHC string
    failed_attempts INTEGER NOT NULL DEFAULT 0,        -- consecutive wrong passwords
    locked_until    TIMESTAMPTZ,                       -- set after too many failures
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
