# Secure Banking CLI

A menu-driven banking application in plain **Core Java 25 + JDBC + MySQL 8**, with no frameworks.
Customers register, log in, open Savings / Current / Fixed-term Deposit / Loan accounts,
deposit, withdraw, transfer, repay loans and view paginated transaction history
or simply type what they want in plain English to a **local AI assistant** (Ollama).

Revature P1 individual project. It is built to the instructor's design document
*P1 Secure Banking CLI HLD/LLD/DDD v2.0*; section numbers like "§18" below refer to it.

> **Security note:** this is an educational banking simulation, not a production banking system.

---

## Contents
1. [Features](#features)
2. [AI banking assistant (extra feature)](#ai-banking-assistant-extra-feature)
3. [Requirements](#requirements)
4. [Setup](#setup)
5. [Running the app](#running-the-app)
6. [Running the tests](#running-the-tests)
7. [Configuration](#configuration)
8. [Project structure](#project-structure)
9. [Design decisions](#design-decisions)
10. [Session timeout vs. database transaction timeout](#session-timeout-vs-database-transaction-timeout)
11. [Known limitations](#known-limitations)
12. [AI usage log](#ai-usage-log)

---

## Features

| ID | Requirement | How it is met |
|---|---|---|
| P1-F01 | Customer registration | Validated name, email, phone, username and password policy; all problems reported at once; unique username/email enforced by DB `UNIQUE` constraints |
| P1-F02 | Authentication | PBKDF2-HMAC-SHA256, 16-byte random salt, 600,000 iterations; no plain-text passwords anywhere; 5 failed logins lock the account for 15 minutes |
| P1-F03 | Account management | Savings and Current open at $0.00 with a system-generated 12-digit account number (Luhn check digit); Fixed-term Deposit and Loan also supported |
| P1-F04 | Deposit | Positive amounts, max 2 decimals; balance + ledger row updated in one transaction |
| P1-F05 | Withdrawal | Invalid amounts and insufficient funds rejected; funds checked under a row lock |
| P1-F06 | Fund transfer | Debit + credit + one ledger row in a single JDBC transaction; any failure rolls back both |
| P1-F07 | Balance enquiry | Only the logged-in customer's own accounts |
| P1-F08 | Transaction history | Paginated (5 per page, N/P navigation), newest first, with date, type, amount, balance after, status and reference |
| P1-F09 | Timeout and logout | 5-minute inactivity timeout, session invalidation, explicit logout; all audited |
| P1-F10 | Input validation | Bad numbers, negatives, >2 decimals, empty input, mistyped account numbers (Luhn) and Ctrl+Z/EOF all handled |
| P1-F11 | Database integrity | PK, FK, UNIQUE and CHECK constraints (incl. `balance >= 0`); `PreparedStatement` for every query |
| P1-F12 | Audit trail | Ledger with reference, timestamp, status and balances-after; `transaction_audit` records every attempt including rejections; `login_audit` records every login outcome |

**Account types (§5.1)**

| Type | Rules |
|---|---|
| Savings | Deposits, withdrawals, transfers; configurable minimum balance (default $0) |
| Current | Deposits, withdrawals, transfers; no overdraft |
| Fixed-term Deposit | Opened by moving ≥ $100 from Savings/Current; locked until the maturity date; then settled in full into Savings/Current and closed |
| Loan (simplified) | Records an amount owed (no disbursement); repaid from Savings/Current; over-payment refused; closes at $0 |

---

## AI banking assistant (extra feature)

Menu option **1. Ask the AI assistant** lets a customer type requests such as
*"put 20 dollars in my current account"*, *"send 50 from savings to 101202600058"*,
*"what's my balance?"* or *"I forgot my password"*.

The model runs **locally** in [Ollama](https://ollama.com) (Llama 3.2, 3B), so customer messages never
leave the computer and no API key is needed. Java talks to it with the built-in `HttpClient`.

**The rule: the AI suggests, the app decides.**

1. The model turns the sentence into a small JSON object (action, account, destination, amount).
2. `IntentParser` re-checks every field and builds one of 7 `AssistantIntent` records, or "not understood".
3. Anything that moves money shows a summary and runs **only if the customer types `y`**.
4. The normal service then applies every rule: ownership, row locks, funds, rollback, audit.

**Defences against AI mistakes** (each covered by `IntentParserTest`):

| Risk | Defence |
|---|---|
| Model invents an account number | The number must appear in what the customer typed, and pass the Luhn check |
| Wrong or odd amounts | Parsed through `Money` (> 0, max 2 decimals); JSON decimals read as `BigDecimal`, never `double` |
| Prompt injection ("ignore your rules…") | Only 6 known actions are accepted; anything else is refused; money actions still need `y` |
| Garbage or non-JSON reply | Treated as "not understood" |
| Password requests | The assistant only opens the normal Change password screen, which asks for the current password |
| Ollama not running | Friendly "assistant not available" message; the rest of the bank works normally |

**Model choice (measured on the development laptop, CPU only):**

| Model | First request (model loading) | Warm request |
|---|---|---|
| llama3 (8B) | 112 s | too slow on CPU |
| **llama3.2 (3B)** | 41 s | **3 s** |

The model is kept loaded for 30 minutes (`keep_alive`) and warmed up in the background while the customer logs in.
The assistant is optional: `assistant.enabled=false` in `app.properties` turns it off.

---

## Requirements

- **JDK 25** (`java -version`)
- **Maven 3.9+** (`mvn -v`)
- **MySQL 8.0** running locally on port 3306
- **Optional:** [Ollama](https://ollama.com) with the `llama3.2` model, for the AI assistant
- Windows PowerShell commands are shown below; other systems work the same way.

---

## Setup

### 1. Create the databases and load the schema

Open the MySQL client:

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p
```

At the `mysql>` prompt (use the full path to your copy of the project):

```sql
CREATE DATABASE banking;
CREATE DATABASE banking_test;

USE banking;
source C:/path/to/banking-cli/sql/mysql/schema.sql
source C:/path/to/banking-cli/sql/sample-data.sql

USE banking_test;
source C:/path/to/banking-cli/sql/mysql/schema.sql

exit
```

- `banking` is the app's database. `sample-data.sql` adds two demo customers (optional, but recommended for a demo).
- `banking_test` is used **only** by the automated tests, which empty it before every test.
- `schema.sql` has no `USE` line on purpose, so the same file builds both databases. It drops and recreates every table, so it is safe to re-run.
- The schema lives in `sql/mysql/` because the design doc keeps one folder per database vendor.

### 2. Set the database credentials as environment variables

Credentials are **never** stored in the project. Run once in PowerShell:

```powershell
[Environment]::SetEnvironmentVariable("DB_URL", "jdbc:mysql://localhost:3306/banking", "User")
[Environment]::SetEnvironmentVariable("DB_USERNAME", "root", "User")
$p = Read-Host "MySQL password"; [Environment]::SetEnvironmentVariable("DB_PASSWORD", $p, "User"); Remove-Variable p
```

Then **close and reopen** your terminal/VS Code so it picks up the new variables. Check with:

```powershell
$env:DB_URL
```

### 3. Optional: set up the AI assistant

Install Ollama from <https://ollama.com>, then:

```powershell
ollama pull llama3.2
curl.exe http://localhost:11434          # should print: Ollama is running
ollama run llama3.2 "hi"                 # loads the model so the first request is fast
```

Without Ollama the app works normally; the assistant option just says it is unavailable.

---

## Running the app

From the project folder:

```powershell
mvn -q compile exec:java
```

(`-q` hides Maven's build output so only the menu shows.)

**Demo logins** (from `sample-data.sql`, fake people):

| Username | Password | Has |
|---|---|---|
| `alice` | `Demo!Pass2026` | Savings $600, Current $250, a **matured** Fixed-term Deposit $500 (ready to settle), Loan $1,900 owed |
| `bob` | `Demo!Pass2026` | Savings $400 (account `101202600058`, handy as a transfer destination) |

Or choose **Register** to create your own customer.

Technical logs go to `logs/banking-cli.log`, never to the screen.

---

## Running the tests

```powershell
mvn test
```

Expected: **97 tests, 0 failures, 1 skipped** (the live Ollama test, skipped on purpose).
Tests need MySQL running, the `banking_test` database, and the `DB_USERNAME` / `DB_PASSWORD`
environment variables. They never touch the `banking` database, and they do not need Ollama.

| Test class | Kind | Covers (design doc test IDs) |
|---|---|---|
| `Pbkdf2PasswordHasherTest` | Unit | Hashing, salt, wrong password, corrupt hash |
| `ReferenceGeneratorTest` | Unit | Luhn check digit catches every single-digit typo; unique references |
| `IntentParserTest` | Unit (fake AI model) | AI assistant safety checks: invented account numbers, bad amounts, invented actions, non-JSON replies, model unavailable |
| `AuthServiceTest` | Service (in-memory fakes) | T01–T05: registration, duplicates, generic login errors, lockout + unlock, password change, logout |
| `AccountServiceTest` | Service (in-memory fakes) | T06 ownership, loan validation, lazy deposit maturity |
| `SessionServiceTest` | Service (in-memory fakes) | T12 inactivity timeout, audited once |
| `JdbcCustomerDAOTest` | DAO integration (MySQL) | CRUD, duplicates, **SQL injection treated as data** |
| `JdbcAccountDAOTest` | DAO integration (MySQL) | All 4 account types, details tables, ownership in SQL |
| `JdbcTransactionDAOTest` | DAO integration (MySQL) | Ledger insert, stable newest-first paging, audit |
| `JdbcTransactionTest` | Integration (MySQL) | **Commit and rollback**, deadlock detection |
| `TransactionServiceTest` | Integration (MySQL) | T07 deposit, T08 overdraw rejected (no ledger row, audited), T13 invalid amounts |
| `TransferAndHistoryTest` | Integration (MySQL) | T09 transfer, **T10 crash mid-transfer rolls back both balances**, T11 invalid destinations, T15 paging |
| `DepositLoanServiceTest` | Integration (MySQL) | T16 deposit maturity/settle, T17 loan repayment |
| `ConcurrencyTest` | Integration (MySQL) | **T14 simultaneous withdrawals can't overspend; opposite transfers don't deadlock** |
| `OllamaLiveTest` | Live (real Ollama) | The real model understands a transfer. Opt-in: `mvn test "-Dtest=OllamaLiveTest" "-Dollama.live=true" "-Dollama.model=llama3.2"` |

---

## Configuration

Non-secret settings live in `src/main/resources/app.properties` (committed). Secrets come only from environment variables.

| Setting | Default | Meaning |
|---|---|---|
| `session.idle.timeout.seconds` | 300 | Log out after this much inactivity |
| `db.query.timeout.seconds` | 10 | Cancel any single SQL statement that takes longer |
| `tx.max.amount` | 1000000.00 | Largest amount per operation |
| `auth.max.failed.logins` | 5 | Wrong passwords before a temporary lock |
| `auth.lock.minutes` | 15 | Length of that lock |
| `password.hash.iterations` | 600000 | PBKDF2 work factor |
| `history.max.page.size` | 50 | Largest history page a caller may request |
| `savings.minimum.balance` | 0.00 | Balance a Savings account must keep |
| `assistant.enabled` | true | Show the AI assistant (false hides it) |
| `ollama.url` | http://localhost:11434 | Where the local Ollama server listens |
| `ollama.model` | llama3.2 | Model used by the assistant |
| `ollama.timeout.seconds` | 60 | Give up on a model reply after this long |

---

## Project structure

```
banking-cli/
├── src/main/java/com/example/banking/
│   ├── app/         BankingApp (wiring), MainMenu, CustomerMenu, ConsoleInput, AppServices
│   ├── ai/          LlmClient, OllamaClient, IntentParser, AssistantIntent (AI assistant)
│   ├── model/       Money, Customer, Account (abstract) + Savings/Current/Deposit/LoanAccount,
│   │                BankTransaction, enums
│   ├── dao/         DAO interfaces; dao/jdbc/ holds the JDBC implementations (all SQL lives here)
│   ├── service/     AuthService, AccountService, TransactionService, DepositLoanService,
│   │                SessionService, TransactionAuditor
│   ├── security/    PasswordHasher, Pbkdf2PasswordHasher, SessionContext
│   ├── validation/  CustomerValidator, AccountValidator, TransactionValidator
│   ├── exception/   BankingException and its subclasses
│   └── util/        AppConfig, ConnectionFactory, JdbcTransaction, ReferenceGenerator
├── src/main/resources/  app.properties, logback.xml
├── src/test/java/       unit, service, DAO-integration, concurrency and AI tests; fakes/
├── sql/mysql/schema.sql 7 tables with PK/FK/UNIQUE/CHECK constraints
├── sql/sample-data.sql  fake demo data
└── pom.xml
```

**Layers:** menus → services → DAOs → MySQL. Menus contain no SQL and no business rules.
Only DAOs contain SQL. Only `JdbcTransaction` calls `commit()` / `rollback()`.
The AI assistant sits beside the menus: it only produces suggestions that go through the same services.

---

## Design decisions

- **Money is `BigDecimal` (Java) / `DECIMAL(19,2)` (MySQL)**, wrapped in an immutable `Money` value object. Amounts with more than 2 decimals are rejected, never silently rounded.
- **Rules live on the model.** `Account` is abstract; each subclass decides `canDebit()` / `canCredit()`. Services call `account.debit(amount)` and the right subclass applies its own rule (polymorphism). There is no public balance setter.
- **Every money operation follows one recipe:** validate → lock the row(s) with `SELECT … FOR UPDATE` → apply the domain rule → update balance(s) → insert one ledger row → commit, or roll back on any error → audit.
- **Deadlock avoidance:** multiple accounts are always locked in ascending id order. Real deadlocks (MySQL 1213) and lock-wait timeouts (1205) retry the whole unit of work, up to 3 times.
- **Ledger vs. audit:** `transactions` holds only completed money movement. Every attempt, including rejections and rollbacks, goes to `transaction_audit`, written on its own connection *after* the money transaction so a rollback can't erase it.
- **Validation twice:** Java gives friendly messages; database constraints (`CHECK (balance >= 0)`, the transaction "shape" check, `UNIQUE`) are the last line of defence.
- **Authorization:** the customer id always comes from the session, never from user input. Ownership is part of the SQL (`WHERE account_id = ? AND customer_id = ?`). Someone else's account gives the same "Account not found." as a non-existent one.
- **Login gives one generic message** for unknown user, wrong password or locked account, and does a dummy hash for unknown users so timing doesn't reveal which usernames exist.
- **Passwords are handled as `char[]`** and wiped after use.
- **Dependency injection by hand:** `BankingApp` builds every object once; everything else receives what it needs. That is what lets service tests use in-memory fakes, a controllable `Clock`, and a fake AI model.
- **The AI never acts on its own:** a sealed `AssistantIntent` type means the menu must handle every possible intent (no `default` case, checked by the compiler), and money actions always need the customer's `y`.

---

## Session timeout vs. database transaction timeout

These are two different timeouts that protect different things.

| | Session (authentication) timeout | Database (query / transaction) timeout |
|---|---|---|
| **Limits** | How long a logged-in customer can stay idle | How long one SQL statement or lock wait may take |
| **Default** | 300 s (`session.idle.timeout.seconds`) | 10 s (`db.query.timeout.seconds`) + MySQL's lock-wait timeout |
| **Enforced by** | Our code: `SessionService.requireActive` | JDBC driver + MySQL (`setQueryTimeout`, InnoDB lock wait) |
| **Protects against** | Someone using an unattended terminal | A stuck query or long lock blocking everyone else |
| **When it fires** | The next time the customer types something after 5 idle minutes | A statement runs too long or waits too long on a row lock |
| **Result** | Session invalidated, TIMEOUT written to `login_audit`, back to the welcome screen | Statement cancelled, the whole transaction rolled back, nothing partially saved; deadlock/lock timeouts are retried |

Console input blocks, so no code runs while the customer is idle. The session check therefore runs the
moment input returns, before anything executes (§20).

---

## Known limitations

- **No background timeout watchdog:** expiry is noticed when the customer next types, not while they sit idle. A watchdog would need non-blocking input (optional in §20).
- **Simplified loans:** no disbursement, no interest accrual or schedule. Deposits record an interest rate but settle the principal only.
- **Single-user terminal app:** no admin role, no account statements or export.
- **Maturity is checked lazily** (when accounts are listed or a settle is attempted), not by a scheduler.
- **AI assistant:** the first request after starting Ollama can take ~40 s on a CPU-only machine while the model loads (the app warms it up in the background); transfers through the assistant need the destination number typed in the message; answers from a language model are not 100% predictable, which is why every money action is confirmed and re-checked.
- Sample data uses fixed ids; load it only into an empty, freshly created schema.

---

## AI usage log

AI assistance (Claude) was used throughout, as allowed by the instructor. I reviewed every file,
ran every change, and verified SQL, security logic and transaction behaviour myself (§39).

| Date | AI helped with | What I verified myself |
|---|---|---|
| Oct 26 | Environment setup steps (JDK, Maven, MySQL, VS Code extensions) | `java -version`, `mvn -v`, `Get-Service *mysql*` outputs |
| Oct 26 | Initial `pom.xml`, schema, first DB connection | `mvn compile` success; connected to MySQL |
| Oct 27 | Aligning schema, exceptions, enums with design doc v2.0 | Ran schema in MySQL; `SHOW TABLES` = 7 in both DBs |
| Oct 27 | `Money`, `Customer`, Account hierarchy, `BankTransaction` | `mvn compile`; read each class and its rules |
| Oct 27 | `AppConfig`, `ConnectionFactory`, env-variable setup | App connected to `banking`; no password in Git |
| Oct 27 | `PasswordHasher`, `Pbkdf2PasswordHasher`, first JUnit tests | `mvn test`: 5 passing |
| Oct 27 | `CustomerDAO`, `JdbcCustomerDAO`, DAO integration tests | 11 passing; SQL-injection test passes |
| Oct 28 | `AuthService`, `CustomerValidator`, `SessionContext`, `LoginAuditDAO` + fakes and tests | 23 passing |
| Oct 28 | Menus, `ConsoleInput`, `BankingApp` wiring, logging | Registered and logged in; checked `customers` and `login_audit` in MySQL |
| Oct 28 | `ReferenceGenerator` (Luhn), `AccountDAO` + tests | 33 passing |
| Oct 29 | `AccountService`, `AccountValidator`, account menu; diagnosing a `%` prompt crash | Found the crash myself by entering a loan; verified the fix |
| Oct 29 | `JdbcTransaction`, row locks, ledger and audit DAOs + tests | 48 passing; rollback test passes |
| Oct 29 | `TransactionService`: deposit / withdraw / balance + tests | 55 passing; tried every rejection path in the app |
| Nov 3 | Transfer, history, crash-injection rollback tests | 67 passing incl. T10 against real MySQL |
| Nov 3 | `SessionService` inactivity timeout + tests | 71 passing; saw the timeout live with a 20-second setting |
| Nov 4 | `DepositLoanService`, `TransactionAuditor` refactor + tests; fixing a misplaced method | 81 passing; opened a deposit, early settle refused, repaid a loan |
| Nov 4 | `ConcurrencyTest` (T14), `sample-data.sql` | 83 passing |
| Nov 5 | README | Rebuilt from a clean database following only this README |
| Nov 9 | AI assistant: Ollama client, intent parser safety checks, menu with confirmation | 97 passing; live test on llama3 (112 s) vs llama3.2 (3 s warm), chose llama3.2; tried it in the app |
