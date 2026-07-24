# Chairman Phase 4B — Tactical Mandate Runtime Enforcement

Phase 4B consumes the normalized Phase 4A mandate at every backend formation
and XI boundary. `ChairmanTacticalMandateEnforcementService` is the canonical
runtime policy point: it reads an immutable `EffectiveChairmanMandate`, forces
the required formation, inserts eligible Chairman slots first, applies only
non-conflicting legacy `CoachPermission` locks, and leaves the remaining
selection to the existing deterministic algorithm.

The policy is used by `TacticController` for saved formation writes, best-XI,
substitutions, and Ask Assistant; by `MatchRoundSimulator` for human/AI
formation choice and automatic selection; and by `LineupAdapter` when reading
saved user tactics. Saved inputs are copied, duplicate/corrupt snapshots keep
their legacy atomic fallback, and the current mandate is evaluated at runtime
so an old saved tactic cannot bypass a new mandate.

Unavailable players are never inserted into a runtime lineup. An unavailable
mandated player remains stored and is omitted temporarily, allowing the normal
selection algorithm to fill the slot. Once eligible again, the mandate applies
automatically. Manual enforcement reports typed `MANDATED_PLAYER_NOT_IN_TEAM`,
`MANDATED_POSITION_INVALID`, `MANAGER_XI_INVALID`, or
`TACTICAL_MANDATE_INVALID` errors as appropriate.

Successful Phase 4A mandate updates invalidate the per-team MatchRoundSimulator
formation, XI, rating, and manager-tactic caches. No global cache reset, schema
change, save/import change, feature flag, frontend change, or Phase 4C runtime is
introduced here.
