# REGENT Market & Trader Adviser Phase 4B

Phase 4B wires the Phase 4A pure models into the existing H2-only REGENT economy. The feature flag remains unchanged and disabled by default.

## Persistent market contract

Every `MARKET_INSTRUMENT` stores `RISK_CLASS` and `RISK_CONFIG_VERSION`. `risk-v1` assigns FMX and SPORTTECH to `SAFE_COMPANY`, MEDIA11 to `SPECULATIVE`, and every club instrument to `CLUB_EQUITY`. Existing H2 saves are migrated deterministically to those assignments.

The existing daily maintenance path remains the only runtime writer. SAFE returns are generated with a maximum 1% magnitude and 60% positive direction probability. SPECULATIVE returns use the hard -50%..+50% pure-core range. CLUB_EQUITY obtains the current canonical `ClubValuationService` valuation, divides it by the instrument's finite issued supply, and applies at most 3% deterministic market noise. All prices are computed server-side with overflow-safe decimal/integer conversions and a positive floor.

The unique instrument/season/day snapshot key, pessimistic instrument lock, and stateless quote key make ordinary Continue, retry, direct daily catch-up, and Fast Forward produce the same closes without rerolling. Supply and cap-table state are never changed by pricing.

## Persistent adviser contract

Adviser skill, reputation, salary, model version, deterministic seed, contract bounds, payroll cursor, state, and hire idempotency key are persisted. Hire terms come only from a server-side catalog. Service methods require a chairman profile owned by the authenticated user and its matching personal account; there is no controller/API in this phase.

Payroll catches up every unpaid contractual day. Each debit uses the existing locked personal account and ledger with an idempotency key derived from contract/day. Insufficient cash terminates the contract without creating a negative balance. A completed contract is closed after its final paid day.

Advice is BUY/SELL/HOLD only and cannot execute a trade. Its inputs are at most eight persisted market snapshots whose date is less than or equal to the requested date. The pure model receives trailing return, observed volatility, risk class, adviser skill, and domain-separated deterministic noise; it never receives a future return or ungenerated quote. The result, confidence, risk, horizon, explanation, and observed inputs are persisted under a unique contract/instrument/day key.

## Save/load and migration

H2 Flyway V6 adds the two instrument columns plus adviser contract and recommendation tables. Save version 11 adds both tables to the exhaustive manifest. Imports from versions 5–10 create empty adviser state and derive risk metadata for older market instrument rows. The existing rollback rehearsal, delete/insert plan, world validation, and single-transaction apply continue to provide atomic import. PostgreSQL/MySQL migrations and compatibility claims are intentionally absent.

## Review commands

The implementer did not run tests or builds. Suggested ATLAS gates:

```text
mvn -Dtest=MarketCoreTest,RegentPhase4BMarketPricingIT,RegentPhase4BTraderAdviserSaveLoadIT test
mvn -Dtest=RegentPhase2MarketIT,RegentPhase2MarketSaveLoadIT,RegentPhase3ClubSaveLoadIT,GameSaveManifestCoverageTest,GameSavePreflightIT test
mvn verify
```
