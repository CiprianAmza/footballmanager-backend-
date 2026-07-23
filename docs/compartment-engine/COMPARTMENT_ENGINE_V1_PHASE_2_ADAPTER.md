# Compartment Engine V1 — Phase 2 adapter boundary

## 1. Scope

Phase 2 delivers **only** the immutable adapter between the current game domain and the pure
Phase 0/1 contextual-rating contract (`ContextualPlayerRatingCalculator` /
`ContextualPlayerRating`). It adds no match score, no team aggregation, no probability sampling, no
RNG, no `MatchPlan`, no persistence, no API and no runtime wiring. The feature flag
`match.engine.compartment.enabled` stays `false`.

The adapter is reachable only from tests. `CompartmentAdapterRuntimeIsolationTest` proves that no
production source outside the `compartment` package references the adapter or the pure calculators.

## 2. Classes

| Class | Kind | Responsibility |
|---|---|---|
| `PlayerAttributeMapping` | pure static | Explicit, once-only mapping of each of the 29 `PlayerAttribute` keys to a `PlayerSkills` field. Fails fast if a key is unmapped. |
| `DomainPlayerSnapshot` | immutable record | JPA-free boundary object: plain scalars plus a defensively copied attribute map. |
| `DomainSnapshotFactory` | pure static | Builds a snapshot from already-loaded `Human` + `PlayerSkills` (+ optional `FormationData` slot), copying scalars only. The only adapter class that touches domain types. No repository/DB access. |
| `CompartmentDomainAdapter` | pure | Maps a `DomainPlayerSnapshot` to the typed `PlayerRatingInput` (applying documented defaults) and delegates to the existing pure calculator to produce the explainable A/M/D `ContextualPlayerRating`. |

Nothing here is a Spring bean; nothing accesses a repository, database, clock or random source. The
same snapshot always yields the same breakdown.

## 3. Field sourcing

| Contract field | Canonical source | Notes |
|---|---|---|
| `position` | `usedPosition` (fine slot key, e.g. `AMC`, `DM`, `WBL`), else `Human.position`, else `UNKNOWN` | **Fine** position key is used as-is; it is *not* collapsed to a base archetype, because the catalogue distinguishes `DM`/`AMC`/`WBL`… from `MC`/`DL`. |
| `role` | `FormationData.role` display name | Mapped via the existing `PlayerRole.fromDisplayName`. |
| `duty` | `FormationData.duty` (`Attack`/`Support`/`Defend`) | Case-insensitive; mapped to the `Duty` enum. |
| attributes | `PlayerSkills` 1–20 fields | All 29 mapped explicitly in `PlayerAttributeMapping`. |
| `fitness` | `Human.fitness` (0–100) | Passed through; the calculator applies its own floor. |
| `morale` | `Human.morale` (0–100) | Passed through; the calculator applies neutral/slope. |
| `positionFamiliarity` | optional canonical familiarity in [0,1] | Not stored on the player; supplied by a caller when available, else defaulted. |
| `roleSuitability` | optional canonical suitability in [0,100] | Not stored on the player; supplied by a caller when available, else defaulted. |

## 4. Defensive defaults for missing / unknown / legacy values

All defaults are documented and observable in tests — no data is silently invented.

| Situation | Behaviour |
|---|---|
| attribute below/above the 1–20 domain (legacy/corrupt) | clamped into `[attributeMin, attributeMax]` |
| attribute absent from the snapshot map | defaults to `attributeMin` (keeps the pure contract complete) |
| used **and** natural position blank/null | falls back to `UNKNOWN_POSITION` ⇒ default position multiplier |
| used position unknown to the catalogue | kept as-is ⇒ default position multiplier |
| role null/blank/unknown | neutral role ⇒ default role multiplier |
| duty null/blank/unknown | `Duty.SUPPORT` (`DEFAULT_DUTY`) |
| familiarity null | `1.0` (`DEFAULT_POSITION_FAMILIARITY`) |
| suitability null | `50.0` (`DEFAULT_ROLE_SUITABILITY`) ⇒ neutral role-fit of `1.0` |

## 5. Intentional limitations (Phase 2 boundary)

- **Context coefficients stay empty (K = 0).** Tactic/player-instruction → K mapping is roadmap
  item 3 and is deliberately out of scope, so no tactic can boost a player's rating in this phase.
  `FormationData.instructions` is therefore read into the snapshot factory's slot overload but not
  translated into rating context here.
- **Familiarity and role suitability are optional inputs, not derived.** The domain does not store a
  0–100 role suitability or a [0,1] familiarity on the player; those are computed at match time by
  `PlayerValueService` / `PlayerRoleService`, which Phase 2 must not wire. The adapter therefore
  accepts them when a caller already holds canonical values and otherwise applies the neutral
  defaults above. Wiring these to the canonical services is a candidate for a later phase and is
  raised as an open question in the TITAN handoff.
- **Used-position resolution is the caller's job.** `FormationData` carries only a grid index; the
  caller resolves the fine used position (today via `TacticService.getPositionFromIndex`) and hands
  it to the factory. The adapter does not depend on `TacticService`.
- No team aggregation, mentality redistribution, matchup/xG, sampling or decision object — those
  remain later roadmap gates.
