-- Existing anonymous accounts have no trusted JWT subject. Claim/migrate them explicitly before access.
ALTER TABLE savings_accounts ADD COLUMN owner_subject VARCHAR(200) NOT NULL DEFAULT 'legacy-unclaimed';
ALTER TABLE savings_accounts ALTER COLUMN owner_subject DROP DEFAULT;
CREATE TABLE account_idempotency (
    id UUID PRIMARY KEY,
    owner_subject VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    account_id UUID NOT NULL REFERENCES savings_accounts(id),
    CONSTRAINT uk_idempotency_owner_key UNIQUE (owner_subject, idempotency_key)
);
