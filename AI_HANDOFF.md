# Canonical match plan — final implementation report

## Control

- Revision: 5 — final
- Owner: CODEX
- Status: COMPLETED
- Base commit: `6ad375f`
- Scope: canonical match plan and live/instant unification
- Final reviewer: CODEX
- Further implementation handoff: none

The Git diff and executable tests are the source of truth. This file is the final
record of the completed review; ownership must not be returned to Claude.

## Outcome

The backend now has one canonical match plan for instant and watched matches:

- the result, goal sides, goal types and goal minutes are prepared once;
- live playback consumes the same persisted slots instead of independently
  inventing another score;
- at each goal minute, the scorer and assist are resolved canonically from the
  eligible players actually on the pitch;
- a manual substitution therefore changes only the eligible contributor set,
  never the prepared score or goal minute;
- instant execution and live execution project the same canonical events into
  scorers, assists, match display and statistics;
- extra time is part of the prepared plan and is played through minutes 91–120;
  shootout kicks remain separate from normal goals;
- same-minute goals remain distinct and ordered by `slotIndex`.

## Persistence and restart safety

The canonical fixture now persists:

- the versioned plan and goal slots;
- participant snapshots, starters, bench, roles and designated takers;
- ordered substitutions and derived appearances/minutes;
- resolved match events;
- the complete deferred commit context (tactics, powers, knockout leg/tie,
  match index and fixture row);
- an exact live checkpoint containing current minute, score, pitch state,
  stamina, cards, counters, timeline and deterministic RNG state;
- versioned animation recipes keyed by `(fixtureKey, slotIndex)`.

Cold recovery restores the exact checkpoint. State, advance and substitute may
use the compatibility fallback, but final commit explicitly requires either the
original in-memory session or a persisted `LiveCommitContext`; it cannot commit
with zero powers, missing tactics or incomplete knockout metadata.

## Idempotency and concurrency

- Fixture-level pessimistic locking serializes plan creation, slot resolution
  and final commit.
- Plan/event creation is atomic and idempotent, including 0–0 matches.
- A committed plan is immutable, even after an algorithm version change.
- Final post-match effects are protected by the durable committed state.
- In-memory `committed` state is changed only after the Spring transaction
  commits; rollback resets the retry state.
- Concurrent commit tests prove one winner and no duplicate fixture/detail or
  post-match side effects.

## Animation and API compatibility

- Canonical goal animations have durable, versioned recipes and replay exactly
  after refresh/restart.
- The live payload exposes canonical animations only when the feature is active.
- With the flag off, `canonicalAnimations` is omitted from serialized JSON;
  legacy clients do not receive a new `null` field.
- The separate experimental `animation-engine-v2` branch/commit is not merged
  by this revision and remains an independent future integration.

## Final independent review fixes

The final Codex pass additionally:

1. added a real instant-vs-live JPA E2E test using the same fixture, seed,
   score and lineups;
2. added a real two-thread transactional final-commit test;
3. prevented context-free cold recovery from reaching `/commit`;
4. corrected stale recovery documentation to match the exact checkpoint model;
5. verified the default rollout flag remains off.

## Validation

- Targeted canonical live, E2E, rollback and concurrency suites: passed.
- Full backend suite: **423 tests, 0 failures, 0 errors, 0 skipped**.
- `git diff --check`: clean.
- `match.engine.matchPlan.enabled`: defaults to `false` for controlled rollout.

## Rollout note

Implementation and review are complete, but activation remains a separate
operational decision. Enable the flag gradually on a fresh test database, run a
multi-season shadow/calibration pass, then promote it to the normal runtime only
after telemetry confirms the intended score and contributor distributions.
