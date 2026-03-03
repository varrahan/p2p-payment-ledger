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