# AI implementation and review handoff

This file coordinates implementation work between Claude and Codex. The Git diff
and executable tests remain the source of truth; summaries in this file are only
the handoff protocol.

## Control

- Revision: 4
- Owner: CLAUDE
- Status: APPROVED
- Base commit: `168a812`
- Scope: canonical match plan and live/instant unification
- Last updated by: CLAUDE

Allowed statuses:

- `IMPLEMENTING`
- `REVIEW_REQUESTED`
- `CHANGES_REQUESTED`
- `APPROVED`
- `COMPLETED`

Only the current owner edits production code. Claude sets `Owner: CODEX` and
`Status: REVIEW_REQUESTED` after finishing a coherent implementation slice.
Codex reviews the actual diff and tests, then sets either `CHANGES_REQUESTED` or
`APPROVED`. When changes are requested, ownership returns to Claude.

## Current Codex review

Revision 4 is **APPROVED**. The resubmission closes every blocking finding:

1. The complete slot schema is validated before sorting, so null, invalid id,
   negative/out-of-range and duplicate-slot input falls back without throwing.
2. XI + bench validation is atomic and requires unique, active PLAYER_TYPE Humans
   belonging to the team; invalid bench entries can no longer be silently dropped.
3. Saved starters use their fielded tactical position for the canonical resolver
   snapshot; bench players retain their natural position until live substitution.
4. The requested edge tests are present and pass.

Non-blocking follow-up: `MatchPlanService` calls `UserContext.isHumanTeam` twice
per match, and that method scans `userRepository.findAll()` each time. Do not
expand this revision for it, but replace it with one cached/preloaded human-team
set before enabling match plans for mass batch simulation.

## Claude implementation report

Slice: saved-lineup adapter. Revision 4 (resubmission — addresses the four
`Current Codex review` findings; direction/mode-split unchanged).

### Files changed (this pass)

- `matchplan/LineupAdapter.java`
  - **(1) Strict slot-schema validation before sort/resolve.** A new pre-pass over
    the parsed `first11` requires: non-null entries, positive player ids, slots in
    `0-36`, and unique slot indices. A `null` element no longer reaches the
    comparator (previously threw). Negative slots, slots above 36, and duplicate
    slot indices are rejected → `AUTO_FALLBACK`. Constants `PITCH_SLOT_MIN=0`,
    `BENCH_SLOT_START=30`, `BENCH_SLOT_MAX=36`.
  - **(2) Atomic XI + bench snapshot.** The bench loop now `return`s the whole
    fallback on any invalid bench entry (was: silently skipped, still USER_SAVED).
    Every saved entry (starter AND bench) must resolve to an existing, non-retired
    `TypeNames.PLAYER_TYPE` Human of this team, unique across both lists.
    `resolveSavedPlayer` now also rejects non-player type ids and retired players.
  - **(3) Used (fielded) position for starters.** Starters are snapshotted in the
    position they were fielded: `TacticService.getBasePosition(tacticService
    .getPositionFromIndex(slot))`, not `Human.position`. `ContributionResolver`
    weights scorer/assist by `Contributor.position`, so an out-of-position starter
    is no longer weighted as his natural role. Bench players keep their natural
    position until live substitution wiring supplies their on-pitch role.
  - New dep: `TacticService`.
- Tests: `LineupAdapterTest` — 8 new focused tests (16 total): null formation
  element, duplicate slot, negative slot, slot above 36, wrong-team bench player,
  duplicate bench player, same-team manager/staff id, retired player, and a natural
  ST fielded in a midfield slot whose `Contributor.position` is the used `MC`. The
  `human()` helper now sets `typeId = PLAYER_TYPE`; all prior tests retained.

### Review findings addressed

1. **Schema validated before sorting/resolving** — null-safe; negative, >36, and
   duplicate slots rejected; positive ids required. No throw, no partial build.
2. **Atomic snapshot** — invalid starter *or bench* entry falls the whole lineup
   back; every id resolves to a non-retired PLAYER_TYPE Human of the team, unique
   across XI + bench.
3. **Fielded position preserved** — starter position derived from the grid slot via
   `getPositionFromIndex` + base-position mapping; bench keeps natural position.
4. **Focused tests added** — see above; existing safe-fallback / no-invented-sub
   guarantees retained.

### Behavioral decisions (unchanged)

- AUTO_FALLBACK for a user team uses automatic selection but invents no subs.
- Availability/injury filtering of saved starters is still out of scope (existence,
  type, retirement, team membership, uniqueness, count, slot schema only).
- Flag `match.engine.matchPlan.enabled` remains OFF; legacy path unchanged.

### Schema effects

None. No entity or column changes in this slice.

### Test commands and results

- `mvn test -Dtest='LineupAdapterTest,MatchPlanFoundationTest,InstantMatchExecutorTest,MatchAppearanceTest,MatchTimelineValidatorTest,KnockoutPlanSplitTest,MatchPlanPersistenceTest,MatchPlanIdempotencyTest,MatchPlanRollbackTest,MatchPlanConcurrencyTest,MatchPlanReloadTest,LiveMatchCommitRescoreTest,LiveMatchSimulationServiceTest'`
  → **Tests run: 84, Failures: 0, Errors: 0** (was 75; +9 new LineupAdapter tests).
- `mvn test` (full backend) → **Tests run: 370, Failures: 0, Errors: 0** (was 361).

### Non-blocking follow-up (acknowledged, NOT in this revision)

- `MatchPlanService.isHumanTeam` scans `userRepository.findAll()` twice per match.
  To be replaced with a cached/preloaded human-team set before enabling match plans
  for mass batch simulation.

### Known gaps

- Injured/suspended saved starters are not treated as stale in this slice.
- Live-session wiring is intentionally NOT part of this slice.

### Requested next step

Live-session wiring: `LiveMatchSession` loads the plan and applies the user's real
substitutions over the canonical timeline.

## Codex review result

**APPROVED for Revision 4.** Codex independently reran both suites:

- targeted matchplan + live suite: **84 tests, 0 failures, 0 errors**;
- full backend suite: **370 tests, 0 failures, 0 errors**.

`git diff --check` is clean. No additional blocking finding remains in this slice.

## Next step after approval

**Immediate action for Claude:** Revision 4 is approved. Re-read this file from
disk, change to `Revision: 5`, `Status: IMPLEMENTING`, and implement the first
coherent LIVE-integration slice. The canonical plan must replace the live engine's
independent goal schedule without yet enabling the feature flag by default.

Acceptance criteria for Revision 5:

1. Use the real `CompetitionTeamInfoMatch` fixture key. Under the flag, create or
   load one persisted canonical plan before playback; refresh/retry must reuse it.
2. `LiveMatchSession` must obtain goal side, minute, phase and type from persisted
   `GoalSlot`s. Remove the live path's independent random goal-minute scheduling
   when the canonical plan is active. Final score and goal chronology must therefore
   be identical to instant execution for the same fixture.
3. Do not preselect the live scorer from the kickoff XI. At each canonical goal
   minute, resolve scorer and assist through the one `ContributionResolver` using
   the players actually on the pitch at that minute. A user-substituted player can
   score afterward; a removed player cannot.
4. Persist each resolved slot/event idempotently using the existing fixture/slot
   identity and goal-before-assist ordering. A refresh cannot replay, duplicate or
   reassign an already resolved goal. Shootout kicks remain outside goal/Scorer stats.
5. Record real user substitutions in the canonical substitution timeline with a
   consecutive per-team sequence, and derive appearances/minutes from that actual
   timeline. Do not mix the AI preplanned substitutions into USER_SAVED live play.
6. Keep misses, saves, corners, cards and commentary cosmetic around the fixed
   canonical goals. They may consume their own RNG but may never alter the plan's
   score, goal side or goal minute.
7. Preserve the legacy path byte-for-byte with `matchPlan.enabled=false`. Do not
   switch the default flag in this revision.
8. Add tests for same fixture instant/live goal chronology, a scorer substituted
   off before a future goal, a substitute scoring after entering, refresh/retry
   idempotency, and a penalty-decided knockout where shootout kicks create no goals.

If separating live preparation from instant `buildAndPersist` is necessary, refactor
shared plan creation rather than duplicating scoring/seed/locking logic. Return
`Owner: CODEX`, `Status: REVIEW_REQUESTED` only after targeted and full suites pass.
