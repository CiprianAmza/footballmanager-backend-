# Compartment Engine V1 Phase 12A: Persisted Scoring Decision

Phase 12A persists one immutable regular-time scoring decision with each AI-vs-AI instant
`MatchPlan`. The decision records the selected engine, score algorithm version, stable seed,
configuration/input fingerprints, score, powers and canonical xG when available. Admin,
Compartment V1, two-axis fallback and scalar fallback are explicit engine kinds.

The AI path checks for a persisted decision before peeking at a predetermined/admin score. A new
candidate is created only after the 90-minute score and any knockout projection are known, then
persisted through a `REQUIRES_NEW` fixture-serialized operation. The first persistent decision
wins; every concurrent or retrying candidate adopts that winner. Game effects begin only after
the outer fixture lock is acquired and the fixture is verified not `COMMITTED`; a committed
fixture therefore produces no duplicate effects. Configuration changes do not resample an
existing decision. This lookup and finalization rule also applies when the current MatchPlan
flag is OFF; the flag controls only creation of a new decision. Terminal visibility is checked
with a fresh `REQUIRES_NEW` scalar status query after the fixture lock, so a retry cannot reuse a
stale outer persistence-context snapshot.

Configuration fingerprints are lowercase SHA-256 values over the canonical compartment and
tactical-model coefficients, excluding rollout flags. Input fingerprints are lowercase SHA-256
values over the canonical immutable teams, tactics and fixture context; repository order and JPA
object identity are excluded. Authoritative Compartment mode requires MatchPlan persistence and
the two rollout flags remain false by default.

Phase 12A does not extend canonical scoring to human, live, standalone, extra-time or penalty
paths and does not change formulas, PMFs, sampler, Flyway, save/load, frontend or Chairman.
