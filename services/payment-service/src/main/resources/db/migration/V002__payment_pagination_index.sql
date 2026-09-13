DROP INDEX idx_payments_merchant_created;

CREATE INDEX idx_payments_merchant_created
    ON payments (merchant_id, created_at DESC, external_id DESC);
