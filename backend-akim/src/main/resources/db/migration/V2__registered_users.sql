-- PostgreSQL preserves the existing simulations foreign key when its target is renamed.
ALTER TABLE akim.anonymous_users RENAME TO users;
ALTER TABLE akim.users ALTER COLUMN token_hash DROP NOT NULL;
ALTER TABLE akim.users
    ADD COLUMN email VARCHAR(254),
    ADD COLUMN username VARCHAR(50),
    ADD COLUMN password_hash VARCHAR(100),
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER';

ALTER TABLE akim.users
    ADD CONSTRAINT users_email_unique UNIQUE (email),
    ADD CONSTRAINT users_role CHECK (role IN ('USER', 'ADMIN')),
    ADD CONSTRAINT users_normalized_email CHECK (
        email IS NULL OR (email = lower(btrim(email)) AND length(email) > 0)
    ),
    ADD CONSTRAINT users_account_fields CHECK (
        (email IS NULL AND username IS NULL AND password_hash IS NULL
            AND token_hash IS NOT NULL AND role = 'USER')
        OR
        (email IS NOT NULL AND username IS NOT NULL AND length(btrim(username)) >= 3
            AND password_hash IS NOT NULL AND length(password_hash) > 0 AND token_hash IS NULL)
    );
