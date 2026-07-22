# Animation Engine V2 — Design

Status: implemented on branch `animation-engine-v2` (base `6ad375f`).
Package: `com.footballmanagergamesimulator.animation` — fully isolated from the
legacy `GoalAnimation*` engine and from `LiveMatchSession` (no production caller
in this branch; integration happens after the Revision 5 canonical refactor).

## 1. Goal

A deterministic **Animation Director** that receives a fully-decided canonical
event and invents *only* the visual representation of the phase:

```
MatchPlan
  → canonical event (score/team/minute/scorer/assist already FINAL)
  → MatchMomentSpec            (immutable truth, input contract)
  → AnimationDirector          (seeded, pure)
  → PlayPattern                (one of 15, chosen deterministically)
  → Choreography → FrameCompiler
  → AnimationReplay (151 frames) + AnimationRecipe (compact, persistable)
  → frontend (ordered queue by minute, slotIndex)
```

The engine can never change score, scoring side, minute, scorer, assister or
any match statistic. If a combination cannot be animated, a safe fallback
pattern is used that preserves every canonical datum.

## 2. Canonical contract — `MatchMomentSpec`

Immutable record, validated on construction:

| Field | Meaning |
|---|---|
| `fixtureKey` | canonical fixture identity (same format as `MatchEvent.fixtureKey`) |
| `slotIndex` | canonical goal-slot index inside the plan (−1 + sequence for non-goal moments) |
| `planSeed` | the `MatchPlan` seed |
| `generatorVersion` | animation generator version the caller wants (current: 1) |
| `minute`, `firstHalfStoppage` | exact canonical minute + stoppage for half detection |
| `scoringTeamId`, `defendingTeamId`, `homeTeamId` | immutable canonical sides |
| `phase` | `OPEN_PLAY`, `PENALTY`, `FREE_KICK`, `CORNER` |
| `outcome` | `GOAL`, `SAVE`, `MISS`, `BLOCKED` — mandatory, immutable |
| `scorerId` | the shooter (the canonical scorer when outcome is GOAL) |
| `assisterId` | nullable; when present must give the final pass |
| `attackingPlayers`, `defendingPlayers` | `PlayerSnapshot` list of players **on the pitch** (id, name, shirt, tactical position, rating) |
| `tacticalContext` | optional mentalities (visual flavour only) |

Constructor rejects: scorer not in the attacking snapshot, assister == scorer
or not in the snapshot, duplicate ids, empty/oversized rosters. Everything in
the spec is treated as immutable truth.

## 3. Identity, seed, idempotency

* Animation identity = `fixtureKey + slotIndex` (`AnimationKey`).
* Seed = `AnimationSeeds.derive(planSeed, fixtureKey, slotIndex, generatorVersion)` —
  FNV-1a-64 over the fixture key folded with SplitMix64 over the numeric parts.
  No `Math.random`, `ThreadLocalRandom`, timestamps or global state anywhere.
* Two independent RNG streams are derived from the seed:
  * `selectionRng` — used only to pick the pattern;
  * `patternRng` — used by the choreography + compiler.
  This split is what makes `AnimationRecipe` replay exact: regenerating from a
  recipe skips selection (pattern is pinned) and re-runs the pattern stream
  from the same seed, producing byte-identical frames.
* Same input ⇒ identical frames after any refresh/restart. Different
  `fixtureKey` or `slotIndex` ⇒ a different (still deterministic) variation.

### `AnimationRecipe` (compact persistent representation)

Contains: identity (fixtureKey, slotIndex), planSeed, derived seed,
generatorVersion, chosen `patternId`, phase, outcome, minute,
firstHalfStoppage, team ids, scorer/assist, motion limits and both participant snapshots.
JSON codec: `AnimationRecipeCodec` (Jackson). Typical size ≈ 2 KB — the 151
frames (~45 KB JSON) do **not** need to be persisted because
`AnimationDirector.replay(recipe)` regenerates them identically.

**Version survival:** the recipe pins `generatorVersion`. The director dispatches
through `AnimationGeneratorRegistry`; the current build ships frozen version 1.
When a future version 2 changes any pattern/compiler behaviour it is registered
beside v1 instead of replacing it, so historical recipes still use the exact v1
code. Unknown versions fail explicitly with `UnsupportedAnimationVersionException`
instead of silently producing different frames. A deployment that intentionally
retires old renderer code must archive/serve the rendered frames before removing
that version. Persisting raw frames remains an optional archival policy, not the
default.

## 4. Multiple animations in the same minute

The v1 boundary `Map<Integer, GoalAnimationData>` loses one of two goals in the
same minute. V2 provides `AnimationQueue`: an ordered collection sorted by
`(minute, slotIndex)`, keyed by `AnimationKey`, that never overwrites — two
goals in minute 63 stay two entries played back-to-back. Minutes are **never**
shifted. This queue (list of `AnimationReplay` or of recipes) is the future
frontend contract.

## 5. PlayPattern library

`PlayPattern` interface: `id()`, `supports(spec)` (phase + outcome + roster
requirements), `weight(spec)`, `choreograph(spec, rng)`. Patterns decide *how
the phase looks*; they cannot touch the outcome. 15 implementations:

| Pattern | Phase | Notes |
|---|---|---|
| THROUGH_BALL | OPEN_PLAY | vertical ball in behind, scorer runs onto it |
| ONE_TWO | OPEN_PLAY | requires assister; give-and-go near the box |
| SHORT_PASSING_SEQUENCE | OPEN_PLAY | 4-pass build-up through midfield |
| SWITCH_OF_PLAY | OPEN_PLAY | long diagonal to the far flank, then inside |
| COUNTER_ATTACK | OPEN_PLAY | recovery deep, two fast vertical passes |
| LONG_BALL | OPEN_PLAY | defender/GK launch, scorer in behind |
| OVERLAP_AND_CROSS | OPEN_PLAY | full-back overlap, cross to the box |
| LOW_CROSS_CUTBACK | OPEN_PLAY | byline cutback to the penalty spot |
| LONG_SHOT | OPEN_PLAY | shot from outside the box (x≈70) |
| CORNER_CROSS | CORNER | in-swinger to the box |
| SHORT_CORNER | CORNER | short exchange, then delivery |
| DIRECT_FREE_KICK | FREE_KICK | scorer is the taker |
| CROSSED_FREE_KICK | FREE_KICK | delivery into the box, scorer finishes |
| PENALTY | PENALTY | GOAL/SAVE/MISS (a blocked penalty is not a thing) |
| SAFE_FALLBACK | all | minimal, always-valid; used when nothing else supports the spec or physics validation fails |

All outcomes are supported by every pattern unless stated. Selection is a
deterministic weighted draw over the eligible patterns (stable library order).

## 6. Physical coherence

Canonical build orientation: attacking team plays left→right toward `x=100`;
goal mouth `y ∈ [44,56]` (2-D top-down; height is not modelled, so "between
the posts and under the bar" maps to this band). After building, the replay is
mirrored when the half/side demands it (`isFirstHalf != attackerIsHome`),
which flips only `x` — outcome, events and possession are untouched.

Enforced rules (constants in `AnimationPhysics`, all configurable):

* Players move via a steering integrator: per-frame step ≤ `max-player-step`
  (default 0.9 units/frame), per-frame velocity change ≤
  `max-player-acceleration` (default 0.45), arrival slow-down — so frame N+1
  always continues frame N; no teleports. Both limits are configured under
  `match.animation.v2`, captured in the recipe and re-used during regeneration.
* Players clamped inside the pitch.
* The ball is either exactly at its carrier's position, a dead ball at a set
  piece spot, or on a continuous quadratic-Bézier flight whose endpoints are
  the *compiled* positions of passer (at release) and receiver (at arrival) —
  possession changes only when the flight ends.
* The scorer is always the last chain node → last touch before the shot; when
  an assister exists it is always the penultimate node → gives the final pass.
* Outcome geometry: GOAL ends on the goal line inside `y ∈ [45.5,54.5]`;
  MISS reaches the goal line outside the posts; SAVE ends exactly on the
  goalkeeper's compiled position (dive scripted to intersect); BLOCKED ends
  exactly on a scripted defender's position between shot origin and goal.
* Only players from the two snapshots appear; tactical positions drive the
  base formation, chain roles and ambient movement.

`AnimationPhysicsValidator` re-checks every invariant on the finished replay;
the director falls back to `SAFE_FALLBACK` if a pattern ever produces a
violation (belt and braces — tests assert all patterns validate cleanly).

## 7. Separation of responsibilities

The engine reads the spec and writes frames. It has no repository access, no
score state, no event persistence. It cannot: decide score, decide goal/no
goal, change scorer/assister/minute, add or remove goals, or touch match
statistics. This is structural (the spec is immutable and the output contains
only presentation data) and tested.

## 8. Compatibility & integration contract

* The legacy `GoalAnimation*` engine is untouched and remains the production
  path.
* Flag: `match.animation.v2.enabled=false` (default **false**,
  `AnimationV2Settings`), alongside the configurable motion limits. Not wired
  into `LiveMatchSession` in this branch to avoid colliding with Revision 5.
* Future integration (post-Rev-5): the live/instant executor builds a
  `MatchMomentSpec` per resolved canonical slot (it already has fixtureKey,
  slotIndex, plan seed, minute, scorer, assist, on-pitch set), calls
  `AnimationDirector.direct(spec)`, stores the returned recipe, and exposes
  `AnimationQueue.ordered()` to the frontend, which plays entries in
  `(minute, slotIndex)` order and never overwrites or reinterprets them.

## 9. Performance envelope (measured by `AnimationPerformanceTest`)

Single animation generation ≈ 1–3 ms; recipe JSON ≈ 2 KB; replay JSON ≈
40–60 KB (151 frames × 22 positions); a 6-goal match ≈ 10–20 ms and ≈ 300 KB
of transient frame payload — regenerated on demand from recipes, so nothing
heavy needs persisting.
