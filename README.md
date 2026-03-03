# P2P Payment Ledger

A Person-to-Person payment backend built with Java 21 and Spring Boot 3. Demonstrates the core engineering principles required in real fintech systems: double-entry accounting, concurrency control, idempotency, event-driven notifications, and regulatory compliance patterns.

> **"P2P" means Person-to-Person** — describing the relationship between parties in a transaction (people sending money to people, as opposed to business payments). Like Venmo, Revolut, or Wise, this system is intentionally centralised: a trusted intermediary holding a shared ledger is what makes payments auditable, reversible, and legally operable. A truly decentralised architecture (blockchain) cannot satisfy KYC/AML/dispute-resolution requirements in most jurisdictions.