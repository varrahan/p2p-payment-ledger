# P2P Payment Ledger

A production-grade peer-to-peer payment backend demonstrating fintech engineering principles: **Data Integrity**, **Concurrency Control**, **Security**, and **Scalability**.

---

## Tech Stack

| Component | Technology |
|---|---|
| Language & Framework | Java 21 + Spring Boot 3 |
| Database | PostgreSQL 16 (ACID, row-level locking) |
| Caching & Rate Limiting | Redis 7 (token bucket, distributed locks) |
| Event Streaming | Apache Kafka (Transactional Outbox) |
| Testing | JUnit 5 + Mockito + Testcontainers |
| Infrastructure | Docker + Docker Compose |

---

## Quick Start

### Prerequisites
- Docker + Docker Compose
- Java 21 (for local development only)

### 1. Configure environment

```bash
cp .env.example .env
# Edit .env — fill in DB_PASSWORD and JWT_SECRET at minimum
```

### 2. Run with Docker Compose

```bash
docker-compose up --build
```

The API will be available at `http://localhost:8080`.

### 3. Run locally (dev)

```bash
# Start infrastructure only
docker-compose up postgres redis kafka -d

# Run the app
./mvnw spring-boot:run
```

---

## API Reference

### Authentication

```http
POST /api/v1/auth/register
Content-Type: application/json

{
  "email": "alice@example.com",
  "password": "securepassword",
  "fullName": "Alice Smith"
}
```

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "email": "alice@example.com",
  "password": "securepassword"
}
```

All subsequent requests require:
```http
Authorization: Bearer <token>
```

### Wallets

```http
POST   /api/v1/wallets?currency=USD        # Create wallet
GET    /api/v1/wallets/my                  # List your wallets
GET    /api/v1/wallets/{walletId}          # Get wallet details

POST   /api/v1/wallets/{walletId}/deposit  # Deposit funds (requires Idempotency-Key)
```

### Transfers

```http
# Initiate transfer — Idempotency-Key header is REQUIRED
POST /api/v1/transfers
Idempotency-Key: <unique-uuid>
Content-Type: application/json

{
  "senderWalletId": "...",
  "receiverWalletId": "...",
  "amount": "100.00",
  "currency": "USD",
  "description": "Rent payment"
}

# Reverse a transfer
POST /api/v1/transfers/{transferId}/reverse
Idempotency-Key: <unique-uuid>

# Get transfer details
GET /api/v1/transfers/{transferId}

# Transfer history for a wallet
GET /api/v1/transfers/wallet/{walletId}?page=0&size=20

# Reconciliation check
GET /api/v1/transfers/reconcile
```

---

## Key Engineering Decisions

### 1. Double-Entry Ledger
Every transfer writes exactly two `ledger_entries` rows — a DEBIT for the sender and a CREDIT for the receiver. Balances are never mutated without a corresponding ledger entry pair. The ledger is append-only and immutable.

### 2. Materialized Balance
`wallets.current_balance` is maintained as a materialized view for O(1) read performance. It is updated **within the same database transaction** as the ledger inserts — never separately. On any discrepancy, the ledger is the source of truth.

### 3. Pessimistic Locking (SELECT ... FOR UPDATE)
Both wallet rows are locked at the start of every transfer transaction. Locks are always acquired in ascending wallet ID order to prevent deadlocks when two users transfer to each other simultaneously.

### 4. Idempotency
Every mutating endpoint requires an `Idempotency-Key` header. Processed keys are stored durably in PostgreSQL (not just Redis) and committed in the same transaction as the transfer. Duplicate requests return the original response.

### 5. Transactional Outbox
Kafka events are not written directly from the transaction. Instead, an `outbox_events` row is inserted as part of the same transaction. A scheduled relay polls and publishes to Kafka, guaranteeing at-least-once delivery without risking split-brain.

### 6. Transfer State Machine
```
PENDING → PROCESSING → COMPLETED → REVERSED
                    ↘ FAILED
```

### 7. Transfer Reversals
Reversals create new, offsetting ledger entries. Original records are never modified, preserving full audit trail.

---

## Running Tests

```bash
# All tests (requires Docker for Testcontainers)
./mvnw test

# Unit tests only (no Docker required)
./mvnw test -Dgroups="unit"
```

### Test Coverage

| Test | What It Verifies |
|---|---|
| Happy path transfer | Correct ledger entries, balance updates |
| Idempotency | Duplicate key returns same result, no double-debit |
| Insufficient funds | Exception thrown, balances unchanged |
| Concurrency (20 threads) | No overdraft, final balance exact |
| Reconciliation | SUM(debits) == SUM(credits) after N transfers |
| Reversal | Offsetting entries, status = REVERSED |
| Currency mismatch | Exception thrown before any DB write |
| Unauthorized sender | Exception when user doesn't own sender wallet |

---

## Project Structure

```
src/main/java/com/p2p/payment/
├── config/          # Spring Security, Kafka configuration
├── controller/      # REST controllers (Auth, Wallet, Transfer)
├── domain/
│   ├── entity/      # JPA entities (User, Wallet, Transfer, LedgerEntry, ...)
│   └── enums/       # TransferStatus, LedgerEntryType, IdempotencyStatus
├── dto/
│   ├── request/     # Input DTOs with validation
│   └── response/    # Output DTOs
├── exception/       # Custom exceptions + GlobalExceptionHandler
├── repository/      # Spring Data JPA repositories
├── scheduler/       # Outbox relay + idempotency key cleanup
├── security/        # JWT service, filter, UserPrincipal, SecurityUtils
└── service/
    └── impl/        # Business logic implementations
src/main/resources/
├── application.yml                    # All config — secrets via env vars
└── db/migration/V1__core_schema.sql   # Flyway migration
```

---

## Security Notes

- Secrets are **never** in source code — all via environment variables (see `.env.example`)
- Passwords are BCrypt-hashed with cost factor 12
- JWT tokens are validated on every request
- Users can only act on wallets they own (authorization enforced in service layer)
- Error responses never expose internal stack traces
- SQL injection prevented by parameterized queries throughout
