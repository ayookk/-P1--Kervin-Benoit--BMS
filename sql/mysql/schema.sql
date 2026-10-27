-- Secure Banking CLI schema (MySQL 8+) - matches design doc v2.0 section 17.1
-- No USE statement on purpose: run it against both `banking` and `banking_test`.
-- Re-runnable: drops tables in reverse dependency order first.

DROP TABLE IF EXISTS transaction_audit;
DROP TABLE IF EXISTS login_audit;
DROP TABLE IF EXISTS transactions;
DROP TABLE IF EXISTS loan_details;
DROP TABLE IF EXISTS deposit_details;
DROP TABLE IF EXISTS accounts;
DROP TABLE IF EXISTS customers;

CREATE TABLE customers (
    customer_id         BIGINT       PRIMARY KEY AUTO_INCREMENT,
    full_name           VARCHAR(120) NOT NULL,
    email               VARCHAR(254) NOT NULL UNIQUE,
    phone               VARCHAR(30),
    username            VARCHAR(80)  NOT NULL UNIQUE,
    password_hash       VARCHAR(255) NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    failed_login_count  INT          NOT NULL DEFAULT 0,
    locked_until        TIMESTAMP    NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_customer_status CHECK (status IN ('ACTIVE','LOCKED','CLOSED')),
    CONSTRAINT chk_failed_count    CHECK (failed_login_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE accounts (
    account_id      BIGINT        PRIMARY KEY AUTO_INCREMENT,
    customer_id     BIGINT        NOT NULL,
    account_number  VARCHAR(34)   NOT NULL UNIQUE,
    account_type    VARCHAR(20)   NOT NULL,
    balance         DECIMAL(19,2) NOT NULL DEFAULT 0.00,   -- LOAN: outstanding principal
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_account_customer FOREIGN KEY (customer_id) REFERENCES customers(customer_id),
    CONSTRAINT chk_account_type    CHECK (account_type IN ('SAVINGS','CURRENT','DEPOSIT','LOAN')),
    CONSTRAINT chk_account_status  CHECK (status IN ('ACTIVE','FROZEN','MATURED','CLOSED')),
    CONSTRAINT chk_account_balance CHECK (balance >= 0),
    INDEX idx_accounts_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE deposit_details (
    account_id     BIGINT        PRIMARY KEY,
    principal      DECIMAL(19,2) NOT NULL,
    term_months    INT           NOT NULL,
    interest_rate  DECIMAL(5,2)  NOT NULL DEFAULT 0.00,
    maturity_date  DATE          NOT NULL,
    CONSTRAINT fk_dep_account FOREIGN KEY (account_id) REFERENCES accounts(account_id),
    CONSTRAINT chk_dep_term   CHECK (term_months > 0 AND principal > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE loan_details (
    account_id        BIGINT        PRIMARY KEY,
    principal_amount  DECIMAL(19,2) NOT NULL,
    annual_rate       DECIMAL(5,2)  NOT NULL DEFAULT 0.00,
    tenure_months     INT           NOT NULL,
    start_date        DATE          NOT NULL,
    CONSTRAINT fk_loan_account FOREIGN KEY (account_id) REFERENCES accounts(account_id),
    CONSTRAINT chk_loan_vals   CHECK (principal_amount > 0 AND tenure_months > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE transactions (
    transaction_id          BIGINT        PRIMARY KEY AUTO_INCREMENT,
    reference_number        VARCHAR(40)   NOT NULL UNIQUE,
    source_account_id       BIGINT        NULL,
    destination_account_id  BIGINT        NULL,
    transaction_type        VARCHAR(20)   NOT NULL,
    amount                  DECIMAL(19,2) NOT NULL,
    status                  VARCHAR(20)   NOT NULL,
    description             VARCHAR(255),
    source_balance_after    DECIMAL(19,2) NULL,
    dest_balance_after      DECIMAL(19,2) NULL,
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_txn_source   FOREIGN KEY (source_account_id)      REFERENCES accounts(account_id),
    CONSTRAINT fk_txn_dest     FOREIGN KEY (destination_account_id) REFERENCES accounts(account_id),
    CONSTRAINT chk_txn_amount  CHECK (amount > 0),
    CONSTRAINT chk_txn_type    CHECK (transaction_type IN ('DEPOSIT','WITHDRAWAL','TRANSFER','LOAN_REPAYMENT')),
    CONSTRAINT chk_txn_status  CHECK (status IN ('PENDING','SUCCESS','FAILED','REVERSED')),
    CONSTRAINT chk_txn_shape   CHECK (
           (transaction_type = 'DEPOSIT'    AND source_account_id IS NULL     AND destination_account_id IS NOT NULL)
        OR (transaction_type = 'WITHDRAWAL' AND source_account_id IS NOT NULL AND destination_account_id IS NULL)
        OR (transaction_type IN ('TRANSFER','LOAN_REPAYMENT')
            AND source_account_id IS NOT NULL AND destination_account_id IS NOT NULL
            AND source_account_id <> destination_account_id)),
    INDEX idx_txn_src_time (source_account_id, created_at, transaction_id),
    INDEX idx_txn_dst_time (destination_account_id, created_at, transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE login_audit (
    audit_id            BIGINT      PRIMARY KEY AUTO_INCREMENT,
    customer_id         BIGINT      NULL,
    username_attempted  VARCHAR(80) NOT NULL,
    outcome             VARCHAR(20) NOT NULL,
    created_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_customer FOREIGN KEY (customer_id) REFERENCES customers(customer_id),
    CONSTRAINT chk_audit_outcome CHECK (outcome IN ('SUCCESS','FAILURE','LOCKED','TIMEOUT','LOGOUT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE transaction_audit (
    audit_id          BIGINT      PRIMARY KEY AUTO_INCREMENT,
    customer_id       BIGINT      NOT NULL,
    reference_number  VARCHAR(40) NULL,
    event_type        VARCHAR(30) NOT NULL,   -- DEPOSIT, WITHDRAWAL, TRANSFER, LOAN_REPAYMENT, ...
    outcome           VARCHAR(20) NOT NULL,   -- SUCCESS, REJECTED, FAILED, ROLLED_BACK
    failure_code      VARCHAR(40) NULL,       -- e.g. INSUFFICIENT_FUNDS; never raw SQL or stack traces
    created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tauditcust FOREIGN KEY (customer_id) REFERENCES customers(customer_id),
    CONSTRAINT chk_taudit_outcome CHECK (outcome IN ('SUCCESS','REJECTED','FAILED','ROLLED_BACK')),
    INDEX idx_taudit_customer (customer_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;