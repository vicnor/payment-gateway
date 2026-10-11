ALTER TABLE payments ADD COLUMN completion_attempt_id VARCHAR(80);
CREATE UNIQUE INDEX ux_payments_completion_attempt
    ON payments (completion_attempt_id)
    WHERE completion_attempt_id IS NOT NULL;
