# Animation Engine V2 — Handoff

- Owner: FABLE
- Status: CHANGES_REQUESTED
- Review revision: 1
- Reviewed commit: `96fa443`
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

## Codex review — changes requested (2026-07-22)

The isolated architecture is promising and both suites pass independently
(`36/36` animation tests, `406/406` full backend tests), but the commit is not
approved yet. The following findings must be addressed before integration.

### P1 — valid canonical goals can be impossible to animate

`FrameCompiler.buildTimeline` sizes a pass from choreography coordinates, but
the receiver starts from the tactical formation and may not reach that point
before the scheduled arrival. The ball is then sent to the receiver's actual
compiled position, which can require an illegal 5-10 units/frame. The normal
pattern fails validation and `SAFE_FALLBACK` can fail in the same way, after
which `AnimationDirector.direct()` throws.

Independent reproduction: standard 11v11, scorer `102` (`DC`), assister `109`
(`AMC`), OPEN_PLAY+GOAL throws `IllegalStateException` with ball steps up to
`9.45`; a broader randomized scorer/assister sweep also found many failures.

Required fix:

- make clip-start positioning/timing account for every chain participant's
  reachable position under the configured speed/acceleration limits;
- make the fallback genuinely total for every contract-valid moment;
- add a scorer/assister position matrix (GK separately according to product
  rules, plus DL/DC/DR/WBL/WBR/DM/MC/wide/AM/ST), partial/red-card rosters and
  property/fuzz coverage asserting that `direct()` never throws.

### P1 — historical generator versioning is currently only a convention

`AnimationGeneratorRegistry.Generator` stores the final concrete
`FrameCompiler`, and `FrameCompiler.compile()` always emits its single static
`VERSION = 1`. Consequently a future v2 compiler cannot actually be registered
beside v1 without changing the same class/code that historical v1 recipes use.
The documented byte-identical survival guarantee is therefore not structural.

Required fix:

- introduce a versioned compiler/generator interface and immutable v1
  implementation/library (`V1FrameCompiler`, `V1PatternLibrary`, or equivalent);
- allow v1 and a synthetic v2 to coexist in the registry;
- add a golden persisted-v1 recipe test proving that registering/rendering v2
  does not change the v1 replay or fingerprint.

### P1 — extra-time direction cannot be represented correctly

`MatchMomentSpec.isFirstHalf()` treats every minute after regular first-half
stoppage as the same half. Both extra-time periods therefore render in the same
direction, and the initial ET direction is not safely derivable from just
`homeTeamId + minute` anyway.

Required fix: persist an explicit canonical period/attacking direction in the
spec and recipe (preferred over inferring it), then test regular stoppage time,
ET first period and ET second period.

### P2 — replay/frame output is externally mutable

`ReplayFrame.positions` is `List<double[]>`; the arrays are exposed directly,
and `AnimationReplay` does not establish deep immutability. A caller can mutate
a validated/enqueued replay in place and change its fingerprint. This was
reproduced independently by assigning `positions().get(0)[0] = 999`.

Required fix: use an immutable coordinate value (`Position`, recommended) or
deep defensive copies on construction and access; add mutation-resistance tests
for frames, replay lists and the queue.

### P2 — deterministic output depends on caller roster order

The same fixture/slot/seed and the same on-pitch player set produces different
frames if the snapshot list is shuffled, because both weighted support picks
and formation/jitter consume caller iteration order. This was reproduced
independently.

Required fix: carry the canonical `participantIndex`/tactical slot into the
snapshot and normalize all selection order (playerId is acceptable only where
slot order is irrelevant). Add shuffled-input tests that define and enforce the
intended canonical behavior.

### P2 — real supported positions WBL/WBR are mapped incorrectly

The game generates and uses `WBL`/`WBR`, but `FrameCompiler.basePosition()`
falls through to central midfield and `positionGroup()` treats them as
attackers. Wing-backs therefore begin centrally and receive attacking ambient
movement. Pattern preference lists also omit them from full-back roles.

Required fix: support the complete production position vocabulary, map WBL/WBR
as wide defenders/wing-backs, include them in appropriate overlap/cross/support
pools, and use production-position fixtures in the physics sweep.

### P2 — `assisterId == null` still renders a clean final assist

For essentially every no-assist open-play sample, patterns synthesize a final
`PASS teammate -> scorer`; the validator only checks the positive-assist case.
That visually contradicts the canonical absence of an assist.

Required fix: when assist is null, render a genuinely unassisted route (solo
carry, loose/rebound/deflection/turnover before the scorer) rather than a clean
final pass, and add the inverse contract assertion.

### Integration gate — durable animation idempotency remains required

The current in-memory `AnimationQueue` intentionally does not survive refresh
or restart. Before enabling the flag, the integration slice must persist the
recipe with a unique `(fixtureKey, slotIndex)` key, transactionally reuse it
under concurrent requests, and prove cold restart/refresh idempotency. This may
land after the canonical Revision 5 rebase, but it remains a release blocker;
the engine must not be presented as end-to-end idempotent before that work.

After the fixes, rerun the dedicated suite, the new adversarial/property tests,
and the full backend suite, then set `Owner: CODEX`,
`Status: REVIEW_REQUESTED`, increment `Review revision`, and include the new
commit hash and exact test totals.
