CREATE TABLE merchants (
    id                  VARCHAR(40) PRIMARY KEY,             -- mer_01HQX...
    name                VARCHAR(255) NOT NULL,
    callback_url        TEXT NOT NULL,
    return_url_pattern  TEXT NOT NULL,
    cancel_url_pattern  TEXT NOT NULL,
    branding            JSONB NOT NULL DEFAULT '{}'::jsonb,
    mode                VARCHAR(8) NOT NULL,                 -- 'test' | 'live'
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE api_keys (
    id                  UUID PRIMARY KEY,
    merchant_id         VARCHAR(40) NOT NULL REFERENCES merchants(id),
    key_prefix          VARCHAR(16) NOT NULL,                -- first 16 chars of the key, indexed
    key_hash            VARCHAR(255) NOT NULL,               -- Argon2id of full key
    mode                VARCHAR(8) NOT NULL,                 -- 'test' | 'live'
    label               VARCHAR(255),
    revoked_at          TIMESTAMPTZ,
    last_used_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_keys_prefix ON api_keys (key_prefix) WHERE revoked_at IS NULL;

CREATE TABLE webhook_secrets (
    id                  UUID PRIMARY KEY,
    merchant_id         VARCHAR(40) NOT NULL REFERENCES merchants(id),
    secret_hash         VARCHAR(255) NOT NULL,
    active              BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    rotated_at          TIMESTAMPTZ
);
