# Compartment Engine V1 Phase 7: Canonical Runtime Input

Phase 7 adds the runtime boundary that converts already-loaded first-XI values into the canonical
Phase 6 input:

```text
Human + PlayerSkills + FormationData + used position
        -> CanonicalRuntimeInputFactory
        -> CanonicalRuntimeTeamInput
        -> Phase 6 canonical team evaluation (future shadow consumer)
```

`RuntimeLineupSlot` validates identity alignment between a player and its already-loaded skills. The
factory sorts the XI deterministically, calls `PlayerCapabilityService.loadAll` once for the full
XI, copies raw mapped attributes, resolves the explicit used position, typed role, duty, role
suitability, persistent trait, tactical instruction, fitness, morale, and capability snapshot.

The resulting `CanonicalRuntimeTeamInput` always contains exactly eleven players, exactly one
goalkeeper, unique player/slot keys, matching tactical-context keys, and defensive immutable copies
ordered by canonical position, occurrence, and player id.

Team tactical null or blank values resolve to the documented neutral labels. Mentality labels are
mapped case-insensitively to the canonical `Mentality` enum; unknown nonblank labels are rejected.
Role absence is the only neutral role case. Unknown or position-incompatible roles and unknown
nonblank duties are rejected. The persistent `stayForward` trait is independent from tactical
`Stay Forward`/`Track Back` instructions; selecting both instructions is rejected.

Phase 7 does not connect to `MatchRoundSimulator`, scoring, `MatchPlan`, frontend APIs, or shadow
mode execution. Runtime scoring and feature flags remain unchanged. Phase 8 has not started.
