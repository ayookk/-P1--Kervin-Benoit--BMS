-- Secure Banking CLI - FAKE sample data for demos (design doc section 26).
-- Run AFTER sql/mysql/schema.sql, on an empty `banking` database:
--     USE banking;
--     source C:/Users/benoi/projects/banking-cli/sql/mysql/schema.sql
--     source C:/Users/benoi/projects/banking-cli/sql/sample-data.sql
--
-- Demo logins (fake people, demo-only password, never reuse it anywhere real):
--     alice / Demo!Pass2026    bob / Demo!Pass2026
-- Passwords are stored as PBKDF2 hashes, exactly as the app writes them.
--
-- Story (balances below are exactly what these ledger rows add up to):
--   Apr 1  Alice deposits 1500 into Savings, opens a 6-month term deposit of 500 from it
--   Apr 2  Alice records a 2000 loan
--   May 1  Alice withdraws 200          May 15 Bob deposits 300
--   Jun 1  Alice transfers 100 to Bob   Jul 1  Alice repays 100 of her loan
--   Aug 1  Alice deposits 250 into Current
--   Oct 1  Alice's term deposit matures -> it shows as MATURED and can be settled in the demo

INSERT INTO customers (customer_id, full_name, email, phone, username, password_hash, status, created_at) VALUES
 (1, 'Alice Example', 'alice@example.com', '555 010 0001', 'alice',
  'pbkdf2_sha256$600000$WGDjF0c4jBZTPaLHVCmVNg==$o+A5Cl6uCaHsafCuCRFXTagAphGHbIUrNQZa5y5GElE=',
  'ACTIVE', '2026-04-01 08:00:00'),
 (2, 'Bob Example', 'bob@example.com', '555 010 0002', 'bob',
  'pbkdf2_sha256$600000$5ZF+TblJRj8ePzgbqtMWwQ==$SNEFD4fd60oQ3YcCV2XRAigSpT0SStu57/cr0BOl0MA=',
  'ACTIVE', '2026-04-01 08:30:00');

-- account numbers: 10 + type digit + 8 digits + Luhn check digit (all valid)
INSERT INTO accounts (account_id, customer_id, account_number, account_type, balance, status, created_at) VALUES
 (1, 1, '101202600017', 'SAVINGS',  600.00, 'ACTIVE', '2026-04-01 08:05:00'),
 (2, 1, '102202600023', 'CURRENT',  250.00, 'ACTIVE', '2026-04-01 08:06:00'),
 (3, 1, '103202600039', 'DEPOSIT',  500.00, 'ACTIVE', '2026-04-01 10:00:00'),
 (4, 1, '104202600045', 'LOAN',    1900.00, 'ACTIVE', '2026-04-02 09:00:00'),
 (5, 2, '101202600058', 'SAVINGS',  400.00, 'ACTIVE', '2026-04-01 08:35:00');

INSERT INTO deposit_details (account_id, principal, term_months, interest_rate, maturity_date) VALUES
 (3, 500.00, 6, 3.00, '2026-10-01');

INSERT INTO loan_details (account_id, principal_amount, annual_rate, tenure_months, start_date) VALUES
 (4, 2000.00, 6.50, 24, '2026-04-02');

INSERT INTO transactions (transaction_id, reference_number, source_account_id, destination_account_id,
                          transaction_type, amount, status, description,
                          source_balance_after, dest_balance_after, created_at) VALUES
 (1, 'TXN-20260401090000-SAMPA2', NULL, 1, 'DEPOSIT',        1500.00, 'SUCCESS', 'Deposit',        NULL,   1500.00, '2026-04-01 09:00:00'),
 (2, 'TXN-20260401100000-SAMPB3', 1,    3, 'TRANSFER',        500.00, 'SUCCESS', 'Transfer',       1000.00, 500.00, '2026-04-01 10:00:00'),
 (3, 'TXN-20260501120000-SAMPC4', 1, NULL, 'WITHDRAWAL',      200.00, 'SUCCESS', 'Withdrawal',     800.00,  NULL,   '2026-05-01 12:00:00'),
 (4, 'TXN-20260515110000-SAMPD5', NULL, 5, 'DEPOSIT',         300.00, 'SUCCESS', 'Deposit',        NULL,    300.00, '2026-05-15 11:00:00'),
 (5, 'TXN-20260601140000-SAMPE6', 1,    5, 'TRANSFER',        100.00, 'SUCCESS', 'Transfer',       700.00,  400.00, '2026-06-01 14:00:00'),
 (6, 'TXN-20260701150000-SAMPF7', 1,    4, 'LOAN_REPAYMENT',  100.00, 'SUCCESS', 'Loan repayment', 600.00, 1900.00, '2026-07-01 15:00:00'),
 (7, 'TXN-20260801160000-SAMPG8', NULL, 2, 'DEPOSIT',         250.00, 'SUCCESS', 'Deposit',        NULL,    250.00, '2026-08-01 16:00:00');

-- every attempt is audited, including one rejected withdrawal (which has NO ledger row)
INSERT INTO transaction_audit (customer_id, reference_number, event_type, outcome, failure_code, created_at) VALUES
 (1, 'TXN-20260401090000-SAMPA2', 'DEPOSIT',        'SUCCESS',  NULL,                 '2026-04-01 09:00:00'),
 (1, 'TXN-20260401100000-SAMPB3', 'TRANSFER',       'SUCCESS',  NULL,                 '2026-04-01 10:00:00'),
 (1, 'TXN-20260501120000-SAMPC4', 'WITHDRAWAL',     'SUCCESS',  NULL,                 '2026-05-01 12:00:00'),
 (1, 'TXN-20260501120500-SAMPH9', 'WITHDRAWAL',     'REJECTED', 'INSUFFICIENT_FUNDS', '2026-05-01 12:05:00'),
 (2, 'TXN-20260515110000-SAMPD5', 'DEPOSIT',        'SUCCESS',  NULL,                 '2026-05-15 11:00:00'),
 (1, 'TXN-20260601140000-SAMPE6', 'TRANSFER',       'SUCCESS',  NULL,                 '2026-06-01 14:00:00'),
 (1, 'TXN-20260701150000-SAMPF7', 'LOAN_REPAYMENT', 'SUCCESS',  NULL,                 '2026-07-01 15:00:00'),
 (1, 'TXN-20260801160000-SAMPG8', 'DEPOSIT',        'SUCCESS',  NULL,                 '2026-08-01 16:00:00');

INSERT INTO login_audit (customer_id, username_attempted, outcome, created_at) VALUES
 (1,    'alice',   'SUCCESS', '2026-04-01 08:55:00'),
 (NULL, 'mallory', 'FAILURE', '2026-04-03 22:10:00'),
 (2,    'bob',     'SUCCESS', '2026-05-15 10:58:00'),
 (2,    'bob',     'LOGOUT',  '2026-05-15 11:05:00');