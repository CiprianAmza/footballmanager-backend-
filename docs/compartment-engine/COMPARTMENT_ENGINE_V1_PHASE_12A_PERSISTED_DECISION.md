# Compartment Engine V1 Phase 12A: Persisted Scoring Decision

Phase 12A persists one immutable regular-time scoring decision with each AI-vs-AI instant
`MatchPlan`. The decision records the selected engine, score algorithm version, stable seed,
configuration/input fingerprints, score, powers and canonical xG when available. Admin,
Compartment V1, two-axis fallback and scalar fallback are explicit engine kinds.

The AI path checks the predetermined score first, then an existing persisted decision. A new
decision is created only after the 90-minute score and any existing knockout projection are
known, and is saved before scorer/event/stat projections begin. A fixture lock and the immutable
decision fields make retries and concurrent callers reuse the winner across `PLANNED`,
`IN_PROGRESS`, `COMPLETED` and `COMMITTED` states. Configuration changes do not resample an
existing decision.

Configuration fingerprints are lowercase SHA-256 values over the canonical compartment and
tactical-model coefficients, excluding rollout flags. Input fingerprints are lowercase SHA-256
values over the canonical immutable teams, tactics and fixture context; repository order and JPA
object identity are excluded. Authoritative Compartment mode requires MatchPlan persistence and
the two rollout flags remain false by default.

Phase 12A does not extend canonical scoring to human, live, standalone, extra-time or penalty
paths and does not change formulas, PMFs, sampler, Flyway, save/load, frontend or Chairman.
