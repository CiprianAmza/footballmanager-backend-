# Compartment Engine V1 — Phase 4 team aggregation

Phase 4 implements the pure `TeamCompartmentAggregator` boundary promised by the architecture. It
still does not wire the compartment engine into runtime, `MatchPlan`, scoring, xG, RNG, save/load,
Spring services or controllers. The feature flag remains off.

## Boundary

Input is immutable and already explainable at player level:

- `ContextualPlayerRating` from the existing rating calculator;
- typed lineup slots as `LineupSlot(position, occurrence)`;
- typed engagement modifiers as `PlayerTrait` and `ForwardInstruction`.

Output is one deterministic `TeamAggregationResult` containing:

- final team Attack (`AT`);
- final team Attack Protection (`AP`);
- raw A/M/D totals before mentality;
- exact mentality split and transfer masses before/after redistribution;
- coverage and exposure inputs plus nonlinear protection reduction;
- deterministic per-player contribution rows;
- channel allocation across `CENTRAL`, `LEFT/RIGHT_HALF_SPACE` and `LEFT/RIGHT_WIDE`.

## Validation

The aggregator validates purely at the boundary:

- lineup must be non-empty;
- exactly one goalkeeper must exist;
- player identity is unique;
- lineup slots are unique;
- occurrences are contiguous per fine position (`DC#1`, `DC#2`, ...), so gaps are rejected;
- the contextual rating position must match the typed slot position.

This keeps aggregation independent of caller ordering while still surfacing duplicate or missing
slot problems explicitly.

## Mentality redistribution

The contract is applied exactly from typed config:

1. Sum adjusted player Attack, Midfield and Defense.
2. Split Midfield into Attack/Protection with the configured mentality shares.
3. Apply the configured Attack<->Defense transfer while conserving total mass.
4. Preserve both the split totals and the post-transfer totals in the breakdown.

`AT` is the post-transfer team Attack. `AP` starts from the post-transfer protection total and is
then reduced only once by the nonlinear exposure formula.

## Wide-channel rules

The architecture required an approximately 20% allocation toward the extremes/wide channels without
fragile string matching. The implemented deterministic rules are:

- `DL`, `DR`, `WBL`, `WBR`, `ML`, `MR` map to `WIDE`;
- `AML`, `AMR` map to `HALF_SPACE`;
- `GK`, `DC`, `DM`, `MC`, `AMC`, `ST` remain `CENTRAL`;
- eligible lateral positions move exactly 20% of their final post-transfer Attack and Protection
  contribution into their typed side-channel;
- the remaining 80% stays in `CENTRAL`.

This preserves team totals exactly while exposing spatial shape in a stable, typed form.

## Engagement, coverage and AP reduction

Behavior precedence stays trait-first, then instruction:

- `REFUSES_DEFENSIVE_WORK` overrides any instruction;
- `STAY_FORWARD` and `TRACK_BACK` apply only when the refuser trait is absent.

The aggregator applies the existing pure formulas without double-applying them:

- Attack gets exactly one behavior multiplier;
- exposure uses exactly one engagement value per player;
- `AP` reduction is applied once from the post-redistribution protection total.

Coverage uses normalized inputs only:

- best `DM` defense contribution;
- `0.55 *` second `DM`;
- best `DC` pace contribution capped at `0.50`.

The final nonlinear protection step remains:

`APfinal = AP * exp(-0.55 * R^1.70)` where `R = max(0, exposure - 0.65 * coverage)`.
