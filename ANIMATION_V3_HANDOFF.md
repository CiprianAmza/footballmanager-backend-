# Animation Engine V3 handoff

Owner: CODEX

Status: REVIEW_REQUESTED

## Delivery

- Branch: `animation-engine-v3`
- Worktree: `/Users/ciprian.amza/IdeaProjects/fm-animation-engine-v3`
- Approved base: `6ad375f0ef5366efa2307509b37b8d5a0f3b8b60`
- Design: `ANIMATION_ENGINE_V3.md`
- Clean-room constraint: no V2 file or commit was copied or cherry-picked.
- No merge was performed.

## Scope delivered

The new `com.footballmanagergamesimulator.animation` package is an isolated,
deterministic presentation engine. It consumes immutable canonical match facts
and returns a replay plus a compact regeneration recipe. It cannot mutate the
score, statistics, match events, repositories or the match plan.

The public boundary consists of:

- `MatchMomentSpec`: canonical `(fixtureKey, slotIndex)` identity, exact minute,
  plan seed, generator version, phase/outcome, teams, scorer/assist, direction,
  tactical context and immutable 22-player snapshot.
- `AnimationDirector`: selects a compatible pattern, compiles and validates the
  replay, then uses `SAFE_FALLBACK` if a specialised pattern is invalid.
- `AnimationRecipe` / `AnimationRecipeCodec`: persistent deterministic input,
  including exact seed, pattern, participant snapshot and physics profile.
- `GeneratorCatalog`: exact-version historical replay dispatch; unknown
  versions fail explicitly.
- `AnimationQueue`: identity-keyed, idempotent queue ordered by `(minute,
  slotIndex)`, preserving collisions such as two goals in minute 63 and
  rejecting conflicting reuse of a canonical identity.

All random choices derive only from `(planSeed, fixtureKey, slotIndex,
generatorVersion)` and use local seeded `Random` instances. No clock, global
random state, `Math.random` or `ThreadLocalRandom` is used.

## Animation coverage

The library implements 14 distinct patterns plus a safe fallback:

`THROUGH_BALL`, `ONE_TWO`, `SHORT_PASSING_SEQUENCE`, `SWITCH_OF_PLAY`,
`COUNTER_ATTACK`, `LONG_BALL`, `OVERLAP_AND_CROSS`, `LOW_CROSS_CUTBACK`,
`LONG_SHOT`, `CORNER_CROSS`, `SHORT_CORNER`, `DIRECT_FREE_KICK`,
`CROSSED_FREE_KICK`, `PENALTY`, and `SAFE_FALLBACK`.

The compiler and validator enforce pitch bounds, immutable coordinates,
bounded player speed and acceleration, continuous ball travel, physical
possession transfer, snapshot-only participants, canonical scorer/assist roles,
direction mirroring and outcome geometry for GOAL, MISS, SAVE and BLOCKED.

## Compatibility and integration boundary

- `match.animation.v3.enabled` defaults to `false`.
- Legacy `GoalAnimation*` code is unchanged.
- `LiveMatchSession` is intentionally unchanged; Revision 5 owns that refactor.
- No database schema or migration is included.
- The intended future persistence key is `(fixtureKey, slotIndex)` and the
  intended payload is recipe JSON, not materialized frames.

Future integration should resolve a completed match slot into a
`MatchMomentSpec`, persist its recipe transactionally, expose an ordered replay
list to the frontend, and keep exact generator implementations available for
historical regeneration. A version may be removed only after archiving its raw
frames.

## Verification

Commands executed from the V3 worktree:

```text
mvn test -Dtest='com.footballmanagergamesimulator.animation.*Test'
Tests run: 31, Failures: 0, Errors: 0, Skipped: 0

mvn test
Tests run: 401, Failures: 0, Errors: 0, Skipped: 0
```

The dedicated tests cover determinism and restart regeneration, recipe
round-trip/version pinning, identity collisions, all outcomes, all 14 patterns,
fallback, scorer/assist contracts, mirroring and a property sweep across more
than 100 seed/phase/outcome combinations.

Development-machine smoke measurements (not a JMH benchmark):

- one animation: 0.56 ms average;
- six animations: 2.44 ms;
- recipe JSON: 3,273 bytes;
- materialized replay JSON: 78,674 bytes.

## Review focus

Please review the immutable schema and version-retention contract first, then
the outcome geometry in `FrameCompiler`, the invariant enforcement in
`AnimationInvariantValidator`, and the persistence boundary represented by
`AnimationRecipeCodec`. Timings are intentionally diagnostic and should not be
treated as portable performance guarantees.
