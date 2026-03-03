# P2P Payment Ledger

A Person-to-Person payment backend built with Java 21 and Spring Boot 3. Demonstrates the core engineering principles required in real fintech systems: double-entry accounting, concurrency control, idempotency, event-driven notifications, and regulatory compliance patterns.

> **"P2P" means Person-to-Person** — describing the relationship between parties in a transaction (people sending money to people, as opposed to business payments). Like Venmo, Revolut, or Wise, this system is intentionally centralised: a trusted intermediary holding a shared ledger is what makes payments auditable, reversible, and legally operable. A truly decentralised architecture (blockchain) cannot satisfy KYC/AML/dispute-resolution requirements in most jurisdictions.

---

## Table of Contents

- [Tech Stack](#tech-stack)
- [Architecture Overview](#architecture-overview)
- [Key Engineering Decisions](#key-engineering-decisions)
- [Prerequisites](#prerequisites)
- [Setup & Running](#setup--running)
- [Configuration Reference](#configuration-reference)
- [API Reference](#api-reference)
- [Notification System](#notification-system)
- [Running Tests](#running-tests)
- [Project Structure](#project-structure)
- [Cloud Deployment](#cloud-deployment)
- [Security](#security)

---

## Tech Stack

| Layer | Technology | Purpose |
|---|---|---|
| Language & Framework | Java 21 + Spring Boot 3.2 | Application runtime |
| Database | PostgreSQL 16 | ACID ledger, pessimistic locking, Flyway migrations |
| Cache & Rate Limiting | Redis 7 | Token bucket rate limiting, new-IP login tracking |
| Event Streaming | Apache Kafka | Transactional Outbox relay, notification routing |
| Email | SendGrid | Transactional email — security alerts, compliance |
| Push Notifications | Firebase FCM | In-app push — transfers, deposits, security alerts |
| Security | Spring Security + JWT (HS256) | Stateless authentication, BCrypt password hashing |
| Testing | JUnit 5 + Mockito + Testcontainers | Unit and integration tests against real infrastructure |
| Infrastructure | Docker + Docker Compose | Local development and containerised deployment |

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                   REST API (8080)                    │
│         Auth · Wallets · Transfers · Devices        │
└──────────────────────┬──────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────┐
│                  Service Layer                       │
│   UserService · WalletService · TransferService     │
│                                                     │
│  Every mutating operation:                          │
│  1. Checks idempotency key (PostgreSQL)             │
│  2. Acquires pessimistic locks (ascending ID order) │
│  3. Writes ledger entries + updates balance         │  
│  4. Writes outbox event — all in ONE transaction    │
└───────┬──────────────────────────┬──────────────────┘
        │                          │
┌───────▼───────┐        ┌─────────▼────────┐
│  PostgreSQL   │        │  Redis           │
│               │        │                  │
│  users        │        │  Rate limiting   │
│  wallets      │        │  (token bucket)  │
│  transfers    │        │  New-IP tracking │
│  ledger_entries        └──────────────────┘
│  idempotency_keys
│  outbox_events│
│  device_tokens│
└───────┬───────┘
        │
┌───────▼────────────────┐
│  OutboxRelayScheduler  │  Polls every 1s
│  (every 1 second)      │  Publishes unpublished
└───────┬────────────────┘  outbox rows to Kafka
        │
┌───────▼────────────────────────────────────────────┐
│                    Apache Kafka                     │
│                                                    │
│  transfer-completed  transfer-reversed             │
│  deposit-confirmed   login-new-ip                  │
│  password-changed    large-withdrawal              │
│  monthly-statement   tos-update                    │
└───────┬────────────────────────────────────────────┘
        │
┌───────▼────────────────────────────────────────────┐
│              NotificationConsumer                  │
│                                                    │
│  Security  (login-new-ip, password-changed,        │
│             large-withdrawal)  →  Push + Email     │
│                                                    │
│  Transactional  (transfer-received,                │
│                  deposit-confirmed)  →  Push only  │
│                                                    │
│  Compliance  (monthly-statement,                   │
│               tos-update)  →  Email only           │
└──────────────┬─────────────────┬──────────────────┘
               │                 │
      ┌────────▼──────┐ ┌────────▼──────┐
      │   SendGrid    │ │ Firebase FCM  │
      │   (Email)     │ │   (Push)      │
      └───────────────┘ └───────────────┘
```

### Transfer Flow 

All 14 steps commit in a single database transaction or roll back together:

1. Rate limit check (Redis token bucket)
2. Idempotency check (PostgreSQL)
3. Mark idempotency key `PROCESSING`
4. Acquire pessimistic locks (`SELECT FOR UPDATE`, ascending ID order)
5. Authorisation check (authenticated user owns sender wallet)
6. Currency validation (sender, receiver, and request all match)
7. Sufficient funds check
8. Create `Transfer` record (`status = PROCESSING`)
9. Insert `DEBIT` ledger entry (sender)
10. Insert `CREDIT` ledger entry (receiver)
11. Update materialized balances (both wallets)
12. Mark transfer `COMPLETED`
13. Write `OutboxEvent` (Transactional Outbox)
14. Mark idempotency key `COMPLETED`

---

## Key Engineering Decisions

### 1. Double-Entry Ledger
Every transfer writes exactly two `ledger_entries` rows — a DEBIT for the sender and a CREDIT for the receiver. The ledger is append-only and immutable. The reconciliation endpoint verifies `SUM(debits) == SUM(credits)` at any time.

### 2. Materialized Balance
`wallets.current_balance` is maintained as a fast, queryable balance. It is always updated **within the same transaction** as the corresponding ledger inserts — never separately. If a discrepancy is ever detected, the ledger is the source of truth.

### 3. Pessimistic Locking
Both wallet rows are locked with `SELECT ... FOR UPDATE` at the start of every transfer. Locks are always acquired in **ascending wallet UUID order** to prevent deadlocks when two concurrent transfers involve the same pair of wallets.

### 4. Idempotency
Every mutating endpoint requires an `Idempotency-Key` header. Keys are stored durably in PostgreSQL and committed in the same transaction as the transfer — not just in Redis. Duplicate requests return the original response with no side effects.

### 5. Transactional Outbox
Kafka events are never published directly from within a transaction. Instead, an `outbox_events` row is written as part of the same transaction. The `OutboxRelayScheduler` polls every second and publishes unpublished rows to Kafka. This eliminates the dual-write problem: if Kafka is unavailable, the event is not lost — it will be delivered when Kafka recovers.

### 6. Transfer State Machine
```
PENDING → PROCESSING → COMPLETED → REVERSED
                     ↘ FAILED
```

### 7. Reversals via Offsetting Entries
Reversals create new ledger entries that offset the original ones. Original records are never modified or deleted, preserving a complete, immutable audit trail — a requirement in most financial regulatory frameworks.

### 8. Notification Channel Strategy
Each notification type is routed to the channel that serves its purpose — not just the most visible channel:

| Category | Channel | Reason |
|---|---|---|
| Security (new IP, password change, large withdrawal) | Push + Email | Immediate alert + permanent security trail |
| Transactional (transfer received, deposit confirmed) | Push only | Fast UX confirmation, no legal requirement for email |
| Compliance (monthly statement, ToS update) | Email only | "Durable medium" requirement under PSD2 / MiFID II |

---

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Docker Desktop | Latest | Required for all infrastructure |
| Java JDK | 21 | Temurin recommended |
| Maven | 3.x | For running tests locally |
| SendGrid account | — | Free tier sufficient — [sendgrid.com](https://sendgrid.com) |
| Firebase project | — | Free tier sufficient — [console.firebase.google.com](https://console.firebase.google.com) |

### Install Java 21

**macOS:**
```bash
brew install --cask temurin@21
```

**Linux:**
```bash
sudo apt install temurin-21-jdk
```

**Windows:** Download the `.msi` from [adoptium.net](https://adoptium.net) — check "Set JAVA_HOME" during install.

---

## Configuration Reference

All configuration is driven by environment variables.

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/p2p_ledger` | PostgreSQL connection URL |
| `DB_USERNAME` | `p2p_user` | Database username |
| `DB_PASSWORD` | — | **Required.** Database password |
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | _(empty)_ | Redis password if auth enabled |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker address |
| `JWT_SECRET` | — | **Required.** Minimum 32 characters |
| `JWT_EXPIRATION_MS` | `86400000` | Token lifetime (default: 24 hours) |
| `SENDGRID_API_KEY` | — | **Required.** SendGrid API key |
| `SENDGRID_FROM_EMAIL` | — | **Required.** Verified sender address |
| `SENDGRID_FROM_NAME` | `P2P Payments` | Display name in email From field |
| `FIREBASE_CREDENTIALS_PATH` | `firebase-service-account.json` | Path to Firebase service account JSON |
| `LARGE_WITHDRAWAL_THRESHOLD` | `1000` | Amount above which a withdrawal triggers a security alert |
| `RATE_LIMIT_TRANSFERS_PER_MINUTE` | `10` | Max transfers per user per minute |
| `IDEMPOTENCY_TTL_HOURS` | `24` | How long idempotency keys are retained |
| `SERVER_PORT` | `8080` | HTTP port |
| `LOG_LEVEL` | `INFO` | Application log level |

---

## Setup & Running

### 1. Clone and configure

```bash
git clone 
cd p2p-payment-ledger
cp .env.example .env
```

Open `.env` and fill in the required values — at minimum:
- `DB_PASSWORD` — any strong password
- `JWT_SECRET` — minimum 32 characters
- `SENDGRID_API_KEY` — from your SendGrid dashboard
- `SENDGRID_FROM_EMAIL` — a verified sender address in SendGrid
- `FIREBASE_CREDENTIALS_PATH` — path to your Firebase service account JSON (see below)

### 2. Set up Firebase

1. Go to [console.firebase.google.com](https://console.firebase.google.com)
2. Create a project (or use an existing one)
3. Go to **Project Settings → Service Accounts → Generate New Private Key**
4. Save the downloaded JSON file as `src/main/resources/firebase-service-account.json`
5. Confirm it is listed in `.gitignore` — **this file must never be committed**

### 3. Start everything

```bash
docker compose up --build
```

First run downloads images and builds the JAR — allow 5–10 minutes. Subsequent runs start in under 30 seconds.

### 4. Verify

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

### Running infrastructure only (for local development)

```bash
# Start PostgreSQL, Redis, and Kafka only
docker compose up postgres redis zookeeper kafka -d

# Run the app with Maven (hot reload)
mvn spring-boot:run
```

### Stopping

```bash
docker compose down          # Stop containers, keep data
docker compose down -v       # Stop containers, wipe all data (clean slate)
```

---

## API Reference

All endpoints (except auth and health) require:
```
Authorization: Bearer <token>
```

### Authentication

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/v1/auth/register` | Register a new user |
| POST | `/api/v1/auth/login` | Login and receive a JWT |
| POST | `/api/v1/auth/change-password` | Change password (triggers security notification) |

**Register:**
```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"password123","fullName":"Alice Smith"}'
```

**Login:**
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"password123"}'
```

**Change password:**
```bash
curl -X POST http://localhost:8080/api/v1/auth/change-password \
  -H "Authorization: Bearer " \
  -H "Content-Type: application/json" \
  -d '{"currentPassword":"password123","newPassword":"newSecurePass456"}'
```

---

### Wallets

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/v1/wallets?currency=USD` | Create a wallet |
| GET | `/api/v1/wallets/my` | List all your wallets |
| GET | `/api/v1/wallets/{walletId}` | Get a specific wallet |
| POST | `/api/v1/wallets/{walletId}/deposit` | Deposit funds (**Idempotency-Key required**) |

**Create wallet:**
```bash
curl -X POST "http://localhost:8080/api/v1/wallets?currency=USD" \
  -H "Authorization: Bearer "
```

**Deposit:**
```bash
curl -X POST http://localhost:8080/api/v1/wallets//deposit \
  -H "Authorization: Bearer " \
  -H "Idempotency-Key: deposit-001" \
  -H "Content-Type: application/json" \
  -d '{"amount":"500.00","currency":"USD"}'
```

---

### Transfers

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/v1/transfers` | Send money (**Idempotency-Key required**) |
| GET | `/api/v1/transfers/{transferId}` | Get transfer details |
| GET | `/api/v1/transfers/wallet/{walletId}` | Transfer history for a wallet |
| POST | `/api/v1/transfers/{transferId}/reverse` | Reverse a transfer (**Idempotency-Key required**) |
| GET | `/api/v1/transfers/reconcile` | Verify ledger balance (SUM debits == SUM credits) |

**Send money:**
```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Authorization: Bearer " \
  -H "Idempotency-Key: transfer-001" \
  -H "Content-Type: application/json" \
  -d '{
    "senderWalletId": "",
    "receiverWalletId": "",
    "amount": "100.00",
    "currency": "USD",
    "description": "Dinner split"
  }'
```

**Transfer history (paginated):**
```bash
curl "http://localhost:8080/api/v1/transfers/wallet/?page=0&size=20" \
  -H "Authorization: Bearer "
```

---

### Device Tokens (Push Notifications)

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/v1/devices/register` | Register a device for push notifications |
| DELETE | `/api/v1/devices/deregister` | Deregister a device (on logout) |

**Register device:**
```bash
curl -X POST http://localhost:8080/api/v1/devices/register \
  -H "Authorization: Bearer " \
  -H "Content-Type: application/json" \
  -d '{"token":"","deviceType":"IOS"}'
```

---

### Health

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| GET | `/actuator/health` | No | Overall application health |
| GET | `/actuator/health/liveness` | No | Is the process alive? |
| GET | `/actuator/health/readiness` | No | Is the app ready to serve traffic? |

---