# Compartment Engine V1 — Phase 3 contextual coefficients

Phase 3 adds a pure boundary from the canonical tactic labels and player instructions to attribute
context coefficients (`K`). It does not wire the future engine into a match path. The compartment
feature flag remains off and `application.yml` is unchanged.

`TacticalContextInput` is an immutable scalar snapshot. `ContextCoefficientMapper` recognizes the
existing team axes (mentality, tempo, passing type, defensive line, pressing and width) and all
player instructions exposed by `PlayerInstructionService`. Neutral or unknown labels add nothing,
so their coefficient is zero. Each recognized signal names only the attributes needed to execute
that instruction; unrelated attributes are absent rather than receiving a generic bonus.

Composition is addition followed by one configured coefficient clamp. Because addition is applied
per attribute and the contribution breakdown is canonically sorted, both coefficients and their
explanation are independent of input instruction order. The result exposes the source, attribute
and delta for every contribution plus every requested/applied clamp pair.

The existing calculator remains the authority for per-attribute context factors and the configured
total context clamp of 0.70–1.30. Its attribute breakdown already exposes requested/applied K,
context factor, base contribution and contextual contribution. The Phase 2 adapter keeps its
original overload with an empty coefficient map (`K=0`); a separate pure/test-only overload accepts
`TacticalContextInput`. Fine positions such as DM/AMC/WBL, role and duty pass through unchanged.

Excluded: team aggregation, score or xG calculation, RNG, MatchPlan, persistence/save/Flyway,
services/controllers/API/frontend and any runtime call site.
