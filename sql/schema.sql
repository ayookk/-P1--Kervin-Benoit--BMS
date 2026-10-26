-- Secure Banking CLI schema (MySQL 8)
-- Re-runnable: drops tables in reverse dependency order first.

USE banking_db;

DROP TABLE IF EXISTS login_audit;
DROP TABLE IF EXISTS transactions;
DROP TABLE IF EXISTS accounts;
DROP TABLE IF EXISTS customers;

-- ===================== CUSTOMERS =====================
CREATE TABLE customers (
    customer_id     BIGINT        NOT NULL AUTO_INCREMENT,
    full_name       VARCHAR(100)  NOT NULL,
    email           VARCHAR(255)  NOT NULL,
    phone           VARCHAR(20)   NOT NULL,
    password_hash   VARCHAR(255)  NOT NULL,   -- PBKDF2 hash + salt, never plain text
    status          ENUM('ACTIVE','LOCKED','CLOSED') NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_customers       PRIMARY KEY (customer_id),
    CONSTRAINT uq_customers_email UNIQUE (email),
    CONSTRAINT uq_customers_phone UNIQUE (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===================== ACCOUNTS =====================
CREATE TABLE accounts (
    account_id      BIGINT        NOT NULL AUTO_INCREMENT,
    customer_id     BIGINT        NOT NULL,
    account_number  VARCHAR(20)   NOT NULL,
    account_type    ENUM('SAVINGS','CURRENT') NOT NULL,
    balance         DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    status          ENUM('ACTIVE','FROZEN','CLOSED') NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_accounts          PRIMARY KEY (account_id),
    CONSTRAINT uq_accounts_number   UNIQUE (account_number),
    CONSTRAINT fk_accounts_customer FOREIGN KEY (customer_id) REFERENCES customers (customer_id),
    CONSTRAINT chk_accounts_balance CHECK (balance >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===================== TRANSACTIONS (ledger) =====================
CREATE TABLE transactions (
    transaction_id          BIGINT        NOT NULL AUTO_INCREMENT,
    reference_number        VARCHAR(40)   NOT NULL,
    source_account_id       BIGINT        NULL,      -- NULL for deposits
    destination_account_id  BIGINT        NULL,      -- NULL for withdrawals
    transaction_type        ENUM('DEPOSIT','WITHDRAWAL','TRANSFER') NOT NULL,
    amount                  DECIMAL(15,2) NOT NULL,
    status                  ENUM('SUCCESS','FAILED','PENDING') NOT NULL,
    source_balance_after       DECIMAL(15,2) NULL,  -- audit: balance after change
    destination_balance_after  DECIMAL(15,2) NULL,
    description             VARCHAR(255)  NULL,
    created_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_transactions      PRIMARY KEY (transaction_id),
    CONSTRAINT uq_transactions_ref  UNIQUE (reference_number),
    CONSTRAINT fk_tx_source         FOREIGN KEY (source_account_id)      REFERENCES accounts (account_id),
    CONSTRAINT fk_tx_destination    FOREIGN KEY (destination_account_id) REFERENCES accounts (account_id),
    CONSTRAINT chk_tx_amount        CHECK (amount > 0),
    CONSTRAINT chk_tx_accounts CHECK (
        (transaction_type = 'DEPOSIT'    AND source_account_id IS NULL     AND destination_account_id IS NOT NULL) OR
        (transaction_type = 'WITHDRAWAL' AND source_account_id IS NOT NULL AND destination_account_id IS NULL) OR
        (transaction_type = 'TRANSFER'   AND source_account_id IS NOT NULL AND destination_account_id IS NOT NULL
                                         AND source_account_id <> destination_account_id)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Speeds up paginated history queries
CREATE INDEX idx_tx_source_date      ON transactions (source_account_id, created_at);
CREATE INDEX idx_tx_destination_date ON transactions (destination_account_id, created_at);

-- ===================== LOGIN AUDIT =====================
CREATE TABLE login_audit (
    audit_id         BIGINT        NOT NULL AUTO_INCREMENT,
    customer_id      BIGINT        NULL,      -- NULL if email didn't match any customer
    attempted_email  VARCHAR(255)  NOT NULL,
    outcome          ENUM('SUCCESS','FAILED','LOCKED','LOGOUT','TIMEOUT') NOT NULL,
    reason           VARCHAR(255)  NULL,
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_login_audit          PRIMARY KEY (audit_id),
    CONSTRAINT fk_login_audit_customer FOREIGN KEY (customer_id) REFERENCES customers (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;