CREATE TABLE savings_accounts (
    id UUID PRIMARY KEY,
    account_number VARCHAR(36) NOT NULL UNIQUE,
    customer_name VARCHAR(200) NOT NULL,
    customer_key VARCHAR(200) NOT NULL,
    account_nick_name VARCHAR(30),
    CONSTRAINT valid_customer_name CHECK (length(trim(customer_name)) > 0),
    CONSTRAINT valid_account_nickname CHECK (account_nick_name IS NULL OR length(account_nick_name) BETWEEN 5 AND 30)
);
CREATE INDEX idx_accounts_customer_key ON savings_accounts(customer_key);
