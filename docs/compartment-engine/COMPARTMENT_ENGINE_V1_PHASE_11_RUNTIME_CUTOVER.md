# Compartment Engine V1 Phase 11: Runtime Cutover

Phase 11 adds a feature-flagged authoritative regular-time score for the calibrated AI-vs-AI
instant cohort. Both `match.engine.compartment.enabled` and
`match.engine.compartment.shadow-enabled` remain false by default; this phase does not activate
the rollout.

The canonical service is fail-open. It checks the flag before evaluating its request supplier,
builds both canonical team inputs, evaluates once, derives the stable `MatchPlanService.seedFor`
seed, and samples the existing capped PMFs with a local `SplittableRandom`. Any invalid input or
runtime failure increments the failure counter and lets the simulator execute the legacy branch.
Successful canonical scores also provide the canonical attack plus attack-protection powers used
by downstream 90-minute match effects.

Admin predetermined scores remain authoritative. Human, live, standalone, extra-time and penalty
paths remain on their existing engines. When the authoritative flag is on, shadow evaluation is
not run for the same fixture. Runtime telemetry is bounded and thread-safe, with
`attempted == succeeded + failed`; the flag-off path does not invoke the request supplier or
increment runtime attempts.

Phase 11 does not change the legacy scorer, RNG, MatchPlan, persistence, frontend, Chairman,
feature-flag defaults, or any Phase 0-10 formula.
