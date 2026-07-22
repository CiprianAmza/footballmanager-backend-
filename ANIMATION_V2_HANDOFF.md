# Animation Engine V2 — Handoff

- Owner: CODEX
- Status: REVIEW_REQUESTED
- Branch: `animation-engine-v2`
- Base commit: `6ad375f` (Use saved lineups in canonical match plans)
- Worktree: `/Users/ciprian.amza/IdeaProjects/fm-animation-engine-v2`
- Scope: isolated, deterministic v2 Animation Director. **No production caller
  in this branch** — `LiveMatchSession` and the Revision 5 canonical refactor
  are untouched; the legacy `GoalAnimation*` engine remains the active path.

## Design chosen

Full design in `ANIMATION_ENGINE_V2.md`. Summary:

```
MatchPlan → canonical event → MatchMomentSpec (immutable truth)
  → AnimationDirector (seeded, stateless)
  → PlayPattern (15-entry library, deterministic weighted pick)
  → Choreography → FrameCompiler (timing, steering physics, ball track,
                                  outcome geometry, half-aware mirroring)
  → AnimationReplay (151 frames) + AnimationRecipe (≈2.6 KB, regenerates
                                                    frames byte-identically)
  → AnimationQueue ordered by (minute, slotIndex) → frontend
```

Key decisions:

- **Identity** `fixtureKey + slotIndex` (`AnimationKey`); **seed** derived via
  FNV-1a-64 + SplitMix64 from `planSeed + fixtureKey + slotIndex +
  generatorVersion` (`AnimationSeeds`). No `Math.random`, no clocks, no global
  state anywhere in the engine.
- Two independent RNG streams (selection vs. pattern/compiler) so a recipe
  replay — which pins the pattern — consumes identical pattern-stream draws
  and reproduces frames exactly.
- **Version freezing**: `AnimationGeneratorRegistry` dispatches recipes
  through their exact generator version (currently only frozen v1). A future
  engine revision registers v2 *beside* v1 instead of editing it, so
  historical recipes keep rendering byte-identically. An unknown version
  fails explicitly (`UnsupportedAnimationVersionException`) rather than
  silently re-rendering differently; replaying a recipe whose result no
  longer validates also fails loudly.
- **Motion limits** (`AnimationMotionLimits`: max step / max acceleration per
  frame) are configurable, pinned inside each recipe, and enforced by both
  the compiler and the validator.
- The compiler owns physics; patterns are small declarative choreographies
  and *cannot* touch outcome, scorer, assister, minute or score. A
  `SafeFallbackPattern` renders any combination no specialised pattern
  supports (e.g. PENALTY+BLOCKED, PENALTY with an assister) and after any
  validation failure, always preserving canonical facts.

## Files added/changed

New package `src/main/java/com/footballmanagergamesimulator/animation/`:

- Contract: `MatchMomentSpec`, `PlayerSnapshot`, `TacticalContext`,
  `AnimationPhase`, `AnimationOutcome`, `AnimationKey`
- Determinism: `AnimationSeeds`, `AnimationMotionLimits`,
  `AnimationGeneratorRegistry`, `UnsupportedAnimationVersionException`
- Output: `AnimationReplay`, `ReplayFrame`, `ReplayEvent`, `AnimationQueue`
- Persistence: `AnimationRecipe` (self-validating, seed/identity checked),
  `AnimationRecipeCodec` (Jackson JSON)
- Engine: `AnimationDirector`, `PlayPattern`, `Choreography`,
  `FrameCompiler`, `AnimationPhysics`, `AnimationPhysicsValidator`,
  `AnimationV2Settings` (feature flag + motion-limit config)

`pattern/` sub-package: `BasePattern`, `PlayPatternLibrary` and the 15
patterns listed below.

Tests: `src/test/java/com/footballmanagergamesimulator/animation/` — 8 test
classes + `AnimationTestFixtures`.

Config: `application.yml` gains

```yaml
match:
  animation:
    v2:
      enabled: false          # default OFF; legacy engine remains active
      max-player-step: 0.9
      max-player-acceleration: 0.45
```

No legacy file was modified. `git diff` against `6ad375f` touches only the
new package, its tests, the two markdown docs and the `application.yml` block
above.

## Schema / API

```java
// Build one spec per resolved canonical slot (all fields are immutable truth):
MatchMomentSpec spec = new MatchMomentSpec(fixtureKey, slotIndex, planSeed,
    AnimationDirector.CURRENT_GENERATOR_VERSION, minute, firstHalfStoppage,
    scoringTeamId, defendingTeamId, homeTeamId,
    phase,            // OPEN_PLAY | PENALTY | FREE_KICK | CORNER
    outcome,          // GOAL | SAVE | MISS | BLOCKED  (mandatory, immutable)
    scorerId, assisterId,             // assister nullable
    attackingPlayers, defendingPlayers, // List<PlayerSnapshot> on pitch NOW
    tacticalContext);                   // optional, cosmetic

AnimationDirector director;             // @Service (flag-agnostic, pure)
var directed = director.direct(spec);   // replay + recipe
AnimationReplay frames = directed.replay();
String json = new AnimationRecipeCodec().toJson(directed.recipe()); // persist this

// After refresh/restart:
AnimationReplay again = director.replay(codec.fromJson(json)); // byte-identical

// Frontend boundary (replaces Map<Integer, GoalAnimationData>):
AnimationQueue queue = new AnimationQueue();
queue.enqueue(frames);
List<AnimationReplay> playback = queue.ordered(); // (minute, slotIndex) asc
```

`AnimationReplay` carries: identity, minute (never shifted), sides, phase,
outcome, patternId, renderedWithVersion, scorer/assister, orientation flags
(`homeAttacksRight`, `scoringTeamAttacksRight`), the ordered player list
(attacking XI then defending XI), 151 `ReplayFrame`s (ball x/y, carrierId,
per-player positions, 0.1 precision) and `ReplayEvent`s
(PASS/SHOT/GOAL/SAVE/MISS/BLOCKED).

## Patterns implemented (15)

THROUGH_BALL, ONE_TWO (requires assister), SHORT_PASSING_SEQUENCE,
SWITCH_OF_PLAY, COUNTER_ATTACK, LONG_BALL, OVERLAP_AND_CROSS,
LOW_CROSS_CUTBACK, LONG_SHOT — OPEN_PLAY; CORNER_CROSS, SHORT_CORNER —
CORNER; DIRECT_FREE_KICK (no-assist only), CROSSED_FREE_KICK — FREE_KICK;
PENALTY (GOAL/SAVE/MISS, no assist); SAFE_FALLBACK — everything.
Each pattern declares what it supports via `supports(spec)`; none can alter
the received outcome.

## Tests and exact results

Animation suite — **36/36 green**:

| Class | Tests | Covers |
|---|---|---|
| AnimationDeterminismTest | 5 | identical frames for identical input; deterministic variation per fixtureKey/slotIndex; stable seed derivation |
| AnimationPhysicsInvariantsTest | 6 | every pattern × phase × outcome × assist × 3 slots sweep (100+ combos) through the validator; outcome never changed; speed/teleport/acceleration caps; stricter configurable limits |
| AnimationOutcomeGeometryTest | 7 | GOAL between the posts; MISS never in the mouth; SAVE intersects keeper; BLOCKED intersects a defender; scorer shoots last (all phases); assister gives the final pass (all phases); only snapshot players |
| AnimationMirrorTest | 3 | 2nd half is the exact x-mirror, outcome/events/possession unchanged; stoppage-time half detection; all outcomes validate mirrored |
| AnimationQueueTest | 4 | two goals in minute 63 kept separate, ordered by slotIndex; (minute, slotIndex) playback order; idempotent re-enqueue |
| AnimationRecipeRoundTripTest | 4 | recipe → identical frames; JSON serialize/deserialize → identical frames; canonical identity carried; motion limits pinned across config changes |
| AnimationContractTest | 5 | defensive copies; invalid identity/teams rejected; seed-identity mismatch rejected; unsupported version fails explicitly; impossible combos → SAFE_FALLBACK with facts intact |
| AnimationPerformanceTest | 2 | envelope below |

Full backend suite: **406/406, 0 failures, 0 errors** (`mvn test`), i.e. the
370 pre-existing tests are untouched and the 36 new ones pass alongside.

Measured envelope (Apple silicon dev machine): avg generation **≈0.6 ms** per
animation; recipe JSON **≈2.6 KB**; full replay JSON **≈51 KB** (151 frames ×
22 players); 6-goal match **≈4 ms** total. Persist recipes, regenerate frames
on demand.

## Known limitations

1. 2-D top-down model: "under the bar" has no z-axis — the goal mouth is the
   y-band [44, 56] on the goal line; MISS renders as wide, not over.
2. Off-ball movement is functional but simple (steered ambient targets +
   small deterministic sway); no defender marking/pressing AI yet.
3. `TacticalContext` is accepted and persisted but not yet used to shape
   choreography.
4. Kits/colors are deliberately out of scope (the legacy `TeamKitResolver`
   path stays with the old engine; the future integration can attach kits at
   the API boundary without touching determinism).
5. Only generator version 1 exists; the registry is the extension point.
6. `AnimationQueue` is in-memory per match; durable storage of recipes (e.g.
   a `match_animation` table keyed by fixtureKey+slotIndex) is left for the
   integration slice, by design.

## Integration instructions (after Revision 5 lands)

1. Where the live/instant executor resolves a due goal slot (it already has
   fixtureKey, slotIndex, plan seed, minute, sides, scorer/assist and the
   actual on-pitch set), build a `MatchMomentSpec` and call
   `AnimationDirector.direct(spec)` when `AnimationV2Settings.isEnabled()`.
2. Persist `AnimationRecipe` (JSON via `AnimationRecipeCodec`) keyed by
   `(fixtureKey, slotIndex)`; on refresh/recovery call `director.replay(...)`.
3. Replace the session's `Map<Integer, GoalAnimationData>` boundary with
   `AnimationQueue` and expose `ordered()` to the frontend; the frontend
   plays entries strictly in `(minute, slotIndex)` order and never merges or
   re-times them.
4. Keep `match.animation.v2.enabled=false` as default; the legacy engine
   remains byte-identical when the flag is off.
5. Cosmetic moments (non-goal SAVE/MISS animations the live feed sprinkles
   in) can use synthetic slot indices above the plan's goal slots, still
   unique per fixture.

## Verification commands

```bash
mvn test -Dtest='com.footballmanagergamesimulator.animation.*Test'   # 36/36
mvn test                                                             # 406/406
```
