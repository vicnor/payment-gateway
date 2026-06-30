CREATE TABLE payments (
    id                      UUID PRIMARY KEY,
    external_id             VARCHAR(40) UNIQUE NOT NULL,        -- pay_01HQX...
    checkout_session_id     VARCHAR(40) NOT NULL,               -- soft FK to dynamo
    merchant_id             VARCHAR(40) NOT NULL,
    merchant_reference      VARCHAR(255) NOT NULL,

    amount                  BIGINT NOT NULL,                    -- minor units
    amount_captured         BIGINT NOT NULL DEFAULT 0,
    amount_refunded         BIGINT NOT NULL DEFAULT 0,
    currency                CHAR(3) NOT NULL,

    status                  VARCHAR(32) NOT NULL,
    payment_method          VARCHAR(32) NOT NULL,               -- 'card'
    payment_method_details  JSONB NOT NULL,

    acquirer                VARCHAR(64),
    acquirer_reference      VARCHAR(255),
    auth_code               VARCHAR(32),

    failure_code            VARCHAR(64),
    failure_message         TEXT,

    metadata                JSONB NOT NULL DEFAULT '{}'::jsonb,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    authorized_at           TIMESTAMPTZ,
    captured_at             TIMESTAMPTZ,

    version                 INTEGER NOT NULL DEFAULT 0          -- JPA optimistic lock
);

CREATE INDEX idx_payments_merchant_created  ON payments (merchant_id, created_at DESC);
CREATE INDEX idx_payments_checkout_session  ON payments (checkout_session_id);
CREATE INDEX idx_payments_status            ON payments (status)
    WHERE status IN ('PENDING','AUTHORIZED');

CREATE TABLE payment_attempts (
    id                  UUID PRIMARY KEY,
    payment_id          UUID NOT NULL REFERENCES payments(id),
    attempt_number      INTEGER NOT NULL,
    acquirer            VARCHAR(64) NOT NULL,
    request_payload     JSONB NOT NULL,                         -- token + amount, no PAN
    response_payload    JSONB,
    acquirer_status     VARCHAR(64),
    duration_ms         INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (payment_id, attempt_number)
);

CREATE TABLE payment_events (
    id              BIGSERIAL PRIMARY KEY,
    payment_id      UUID NOT NULL REFERENCES payments(id),
    event_type      VARCHAR(64) NOT NULL,
    from_status     VARCHAR(32),
    to_status       VARCHAR(32),
    payload         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_events_payment ON payment_events (payment_id, created_at);

-- Outbox table for reliable SNS publishing
CREATE TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_id    VARCHAR(40) NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
