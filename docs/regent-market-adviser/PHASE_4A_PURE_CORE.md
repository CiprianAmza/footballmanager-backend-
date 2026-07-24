# REGENT Market & Trader Adviser — Phase 4A pure core

This checkpoint is deliberately standalone. It has no Spring annotations, entity references, persistence, save/load, API, runtime wiring, automatic trading, feature activation or dependency on `ClubValuationService`. A later phase must own those adapters and preserve this core's explicit version fields.

## Determinism and versioning

Every calculation receives `MarketQuoteKey(saveSeed, instrumentId, day, saveVersion)` and a model/profile version. The stateless random source hashes all of these fields plus a domain purpose (`safe-direction`, `speculative-return`, `club-equity-noise`, or `adviser-observation`). Consequently retry order cannot consume a shared RNG sequence, and quote randomness cannot influence adviser noise.

Changing either save or algorithm version intentionally makes a separate deterministic universe. Persistence must store the version used for a completed close; this phase does not decide that storage contract.

## Price invariants

| Class | Formula | Non-negotiable invariant |
| --- | --- | --- |
| `SAFE_COMPANY` | `direction * U(0, maximumDailyMagnitude)` | magnitude is in `0..1%`; direction probability is strictly `>50%` and at most `100%`, while individual days may be negative |
| `SPECULATIVE` | `return = U(0,1) - 0.50`; `close=max(open * (1+return), floor)` | return and realized opening-to-close movement are hard-bounded to `[-50%, +50%]`; floor is strictly positive and an opening below it is rejected |
| `CLUB_EQUITY` | `reference=valuation/issuedSupply`; `quote=max(reference*(1+noise), floor)` | finite positive supply; bounded noise only; valuation remains external |

Prices and returns use `BigDecimal`, so multiplying a legitimate amount around `Long.MAX_VALUE` cannot overflow a `long`. A speculative opening must already meet the positive floor: otherwise lifting it to the floor could create a real daily gain above the hard limit. The current generator is continuous and can approach either endpoint; the endpoints remain part of the contractual inclusive bound for versioned implementations.

## Adviser limits

`TraderAdviser` holds immutable skill, reputation, daily salary, contract period and model version. `TraderAdviceModel` consumes only an `AdviserSignal` made from observed trailing return and volatility. Its signal instrument must exactly equal the quote-key instrument before deterministic noise is sampled. It does not accept a future return, quote result, mutable account, holdings, supply, or execution service.

Skill reduces deterministic observation noise and raises confidence; it does not make advice correct. Advice is only an immutable `BUY`, `SELL` or `HOLD` recommendation with horizon, confidence, risk and an explanation. A future phase must validate contract status and payment and must not treat confidence as a trade authorization.

## Explicit phase limits

No claim is made here about price persistence/idempotent closing, concurrency, financial authorization, cash/holdings, cap-table mutation, salary payment, user permissions, or UI disclosure. Those require a separately reviewed integration phase after the TITAN successor is canonical.
