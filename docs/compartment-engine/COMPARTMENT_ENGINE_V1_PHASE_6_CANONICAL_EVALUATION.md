# Compartment Engine V1 Phase 6: Canonical Evaluation

Phase 6 adds a pure, deterministic bridge for evaluating canonical player context as a team:

```text
PlayerCapabilitySnapshot
        -> PlayerCapabilityResolver
        -> CanonicalPlayerContextAdapter
        -> ContextualPlayerRating
        -> CanonicalTeamEvaluationAdapter
        -> TeamCompartmentAggregator
```

`CanonicalLineupPlayer` contains only copied scalar values, typed position/role/duty values,
attribute values, traits, instructions, and the immutable Phase 5 capability snapshot. The player
adapter resolves persisted or legacy familiarity, constructs the existing domain snapshot, and
delegates rating calculation to the existing pure adapter.

Position familiarity is applied exactly as `positionFamiliarityRating / 20.0`: rating 20 is 1.0,
rating 10 is 0.5, and rating 1 is 0.05. The role familiarity rating and left/right foot ratings
are returned in the explainability result, but do not change the numeric rating in this phase.
Role suitability remains the separately calculated 0-100 input. A missing role is the documented
legacy-compatible neutral case: empty display name, familiarity 10, suitability 50.0, and no role
multiplier effect.

The team adapter evaluates each player with its explicit non-null `TacticalContextInput`, builds
the existing `LineupSlot` and `PlayerCompartmentInput` values, and calls
`TeamCompartmentAggregator` once. Results are defensively copied and sorted by canonical position,
occurrence, and player id.

There is no repository, JPA, Spring, runtime match, scoring, `MatchPlan`, `TacticalScoreService`,
simulation, or frontend wiring in Phase 6. Foot and role-familiarity multipliers are deliberately
deferred until a later calibration phase so this bridge remains explainable and numerically scoped.

Phase 7 has not started.
