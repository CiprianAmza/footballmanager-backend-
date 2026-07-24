# Compartment Engine V1 Phase 12B: Canonical Match Effects

For an AI-vs-AI fixture with a persisted `MatchScoringDecision`, match effects
are a deterministic projection of that decision, its `KnockoutPlanSplit`, and
the persisted canonical goal/assist events.

The canonical projection has three boundaries:

1. `CanonicalMatchEffectsInput` validates the decision, team identity, split,
   and defensively orders the immutable event list.
2. `CanonicalMatchStatsValidator` rejects inconsistent goal/assist data before
   statistics are persisted. Extra-time goals are football goals; shootout
   kicks are never match events, scorers, shots, xG, or football-score goals.
3. `CanonicalMatchStatsSeed` derives a domain-separated SHA-256 seed from the
   persisted decision identity and every split value. The canonical stats
   generator uses a local `Random`, so retries with the same input reproduce
   the same statistics without consuming the legacy RNG stream.

For `COMPARTMENT_V1`, persisted xG values are copied exactly into match stats
after conversion to hundredths. Fallback engines retain the existing stats
generator and receive only the deterministic local seed. The legacy stats
method remains the path for non-durable matches.

The AI durable path persists canonical events and scorer projections, then
persists canonical match stats, and only afterward marks the MatchPlan
`COMMITTED`. Human, live, standalone, scoring, sampler, persistence schema,
save/load, and feature-flag behavior are outside this phase.
