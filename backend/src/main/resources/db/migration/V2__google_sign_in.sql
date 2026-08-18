-- Accounts can now be reached by password, by Google, or by both.

-- A Google-created account has no password, and inventing a random one would be a lie the
-- login path could not tell apart from a real credential.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

-- Google's `sub` claim: stable for the life of the account, and unlike the email address it
-- never changes hands. It, not the email, is what identifies a returning Google user.
ALTER TABLE users ADD COLUMN google_subject VARCHAR(255);

-- Partial, so the many password-only rows do not collide on NULL.
CREATE UNIQUE INDEX ux_users_google_subject ON users (google_subject) WHERE google_subject IS NOT NULL;

-- An account nobody can sign in to is a bug, not a state worth representing.
ALTER TABLE users ADD CONSTRAINT ck_users_has_credential
    CHECK (password_hash IS NOT NULL OR google_subject IS NOT NULL);
