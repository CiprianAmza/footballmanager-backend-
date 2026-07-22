# Animation Engine V3 — Clean-room design

Branch `animation-engine-v3`, based directly on approved commit `6ad375f`.
This design is independent of `animation-engine-v2`: no file or commit from
that branch is copied or cherry-picked.

## Boundary

```text
MatchPlan -> resolved canonical event -> MatchMomentSpec
          -> AnimationDirector -> PlayPattern -> PlayScript
          -> FrameCompiler -> AnimationReplay -> ordered frontend queue
```

`MatchMomentSpec` is immutable truth. It owns the fixture/slot identity, plan
seed and generator version, exact minute, teams, phase, mandatory outcome,
canonical shooter/scorer and assist, the on-pitch participant snapshot,
tactical positions/roles and optional tactical context. The animation package
has no score, statistics, match-event or repository mutation API.

## Identity and recovery

- Identity: `(fixtureKey, slotIndex)`.
- Seed: stable mixing of exactly `(planSeed, fixtureKey, slotIndex,
  generatorVersion)`.
- Randomness: only locally constructed seeded `java.util.Random` instances;
  never clocks, global state, `Math.random` or `ThreadLocalRandom`.
- `AnimationRecipe` persists canonical identity/facts, seed, exact pattern,
  participants and physics profile. Frames regenerate deterministically after
  refresh/restart.
- Generator implementations are dispatched by exact version. Historical
  implementations remain frozen; unknown versions fail explicitly. If a
  version is deliberately removed, its raw frames must be archived first.
- `AnimationQueue` is keyed by event identity and sorts by `(minute,
  slotIndex)`. Same-minute events are distinct and minutes are never moved.
  Re-enqueueing an identical replay is idempotent; reusing the same identity
  with changed canonical facts, events or frames is rejected.

## Pattern architecture

Every `PlayPattern` declares its phase, supported outcomes, requirements,
selection weight and a small declarative `PlayScript`. Pattern code may shape
touch locations and curves but cannot alter canonical facts. The library has:

THROUGH_BALL, ONE_TWO, SHORT_PASSING_SEQUENCE, SWITCH_OF_PLAY,
COUNTER_ATTACK, LONG_BALL, OVERLAP_AND_CROSS, LOW_CROSS_CUTBACK,
LONG_SHOT, CORNER_CROSS, SHORT_CORNER, DIRECT_FREE_KICK,
CROSSED_FREE_KICK and PENALTY, plus SAFE_FALLBACK.

## Physics

The compiler builds in canonical left-to-right orientation and mirrors x only
after compilation when match half/team direction requires it.

- immutable `PitchPoint` coordinates;
- bounded steering for players, with recipe-pinned max speed/acceleration;
- pitch bounds enforced every frame;
- carried ball exactly equals the carrier position;
- continuous quadratic Bezier pass/shot flights;
- receiving possession starts only at physical arrival;
- canonical scorer performs the final shot;
- canonical assist provides the last pass;
- GOAL crosses the goal line inside the mouth;
- MISS reaches the goal-line area outside the mouth;
- SAVE ends at the goalkeeper;
- BLOCKED ends at a defending outfield player;
- only snapshot participants appear.

`AnimationInvariantValidator` checks canonical metadata, player speed and
acceleration, ball continuity, possession, final roles, bounds, mirroring-safe
outcome geometry and snapshot membership. A specialised pattern that fails is
replaced by SAFE_FALLBACK; an invalid fallback is never returned.

## Compatibility

The legacy `GoalAnimation*` engine remains untouched. V3 is isolated behind
`match.animation.v3.enabled=false`; this branch does not wire the flag into
`LiveMatchSession` while Revision 5 owns that refactor. Later integration will
persist recipe JSON under `(fixtureKey, slotIndex)` and expose an ordered list
to the frontend.

## Verification snapshot

On 2026-07-22 the dedicated suite passed 31/31 tests and the full Maven suite
passed 401/401 tests. A non-JMH smoke measurement on the development machine
reported 0.56 ms average generation time, 2.44 ms for six animations, a 3,273
byte recipe and a 78,674 byte materialized replay. Timing is machine-dependent;
the persistent recipe, rather than frames, is the intended storage format.
