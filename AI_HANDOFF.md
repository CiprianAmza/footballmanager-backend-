# AI implementation and review handoff

This file coordinates implementation work between Claude and Codex. The Git diff
and executable tests remain the source of truth; summaries in this file are only
the handoff protocol.

## Control

- Revision: 3
- Owner: CLAUDE
- Status: APPROVED
- Base commit: `1bc5fa0`
- Scope: canonical match plan and live/instant unification
- Last updated by: CODEX

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

Before starting the saved-lineup adapter slice, resolve these items:

1. `COMMITTED` is protected by the reuse policy, but no production path currently
   moves a plan from `COMPLETED` to `COMMITTED`. Add a transactional operation and
   call it as part of the real result/statistics commit. A test-only status change
   does not activate the production guarantee.
2. Use one canonical substitution order. `Lineup` currently executes by
   `(minute, sequence)`, while persistence reloads by `(teamId, subIndex)`. Define
   `sequence` as unique and consecutive per team, validate nondecreasing minutes,
   and use sequence consistently for execution, persistence, and reload.
3. Protect participant ordering in the schema with uniqueness on
   `(match_plan_id, team_id, participant_index)` and load participants with an
   explicit `ORDER BY teamId, participantIndex`.
4. Add a round-trip test: persist a plan, reconstruct both lineups exclusively
   from `MatchParticipant` and `MatchSubstitution`, execute again, and assert the
   same canonical events and contributors.

## Claude implementation report

All four review items are addressed.

### Files changed

Production:
- `matchplan/MatchPlanService.java` — `markCommitted(fixtureKey)`; COMMITTED-immutable
  reuse/regeneration policy; persists participants + substitutions + derived
  appearances; validation; stale-version regeneration via `deletePlanArtifacts`.
- `matchplan/MatchPlanningService.java` — `ALGORITHM_VERSION = matchplan-2`.
- `matchplan/Lineup.java` — bench field; immutable defensive copies; `SubMove.sequence`;
  sort by `sequence` only; `Appearance` with nullable `exitMinute`.
- `matchplan/LineupAdapter.java` — passes bench to `Lineup`; assigns sub `sequence`.
- `matchplan/MatchParticipant.java` (new) — canonical squad + resolver snapshot
  (rating/fitness/finishing/passing/vision/takers) + name + `participantIndex`.
- `matchplan/MatchSubstitution.java` (new) — canonical, `subIndex`-ordered subs.
- `matchplan/MatchAppearance.java` (new) — derived projection (nullable exit + minutes).
- `matchplan/MatchTimelineValidator.java` (new) — strict XI/bench/sub validation.
- `repository/Match{Participant,Substitution,Appearance}Repository.java` (new) — ordered loads.
- `service/MatchRoundSimulator.java` — ET/shootout split wiring; calls `markCommitted`
  after result+Scorer+stats inside the `simulateRound` transaction.

Tests (new): `MatchPlanReloadTest`, `MatchAppearanceTest`, `MatchTimelineValidatorTest`,
`KnockoutPlanSplitTest`. Updated: idempotency/concurrency/executor tests.

### Review items

1. `markCommitted(fixtureKey)` is transactional and is called from
   `MatchRoundSimulator` after the result, `Scorer` and stats are persisted, inside
   the `@Transactional simulateRound`. COMMITTED is never regenerated (even on stale
   version); only PLANNED/COMPLETED regenerate.
2. Single order rule: `SubMove.sequence` is consecutive per team with nondecreasing
   minutes; `Lineup` sorts by `sequence`; persistence and reload load by `sequence`
   (`subIndex = sequence`); validator enforces consecutive sequence + nondecreasing minutes.
3. `MatchParticipant` has unique `(match_plan_id, team_id, participant_index)` and is
   loaded via `findByMatchPlanOrderByTeamIdAscParticipantIndexAsc`.
4. `MatchPlanReloadTest` persists a plan, rebuilds both lineups exclusively from
   `MatchParticipant` + `MatchSubstitution` (resolver snapshot), re-executes on the
   same seed, and asserts identical goals + scorers.

### Behavioral decisions

- Reuse keyed on plan status + version, not event existence (0-0 reuses correctly).
- Appearance boundary: a finisher has `exitMinute = null`, so a 90'/120' goal is his.
- Resolver snapshot persisted so a refresh cannot change scorers if player data drifts.
- Flag `match.engine.matchPlan.enabled` remains OFF; legacy path unchanged.

### Schema effects

New tables (Hibernate auto-DDL): `match_plan`, `match_plan_goal_slot`, `match_participant`,
`match_substitution`, `match_appearance`. `match_event` gains `fixture_key`, `slot_index`,
`event_order` + unique `(fixture_key, slot_index, event_type)` (NULL fixture_key rows stay
distinct, so legacy events are unaffected). No data migration; historical rows keep old columns.

### Revision 3 — response to review

Review item 1 addressed: `MatchPlanReloadTest` is now a genuine cold round trip.
After `buildAndPersist` it calls `entityManager.flush()` + `entityManager.clear()`,
reloads the plan / participants / substitutions / events from their repositories,
rebuilds both lineups only from the reloaded rows, and re-executes on the PERSISTED
plan (resolved slots — no `planning.plan`). It asserts full ordered event identity
(`slotIndex`, `eventOrder`, `eventType`, `minute`, `teamId`, `playerId`, name) for
goals AND assists, with no sorting in the assertion.

### Test commands and results

Surefire 2.22.2 does not accept the package wildcard (it exits `No tests were
executed!`); the valid command is the explicit class list:

- `mvn test -Dtest='MatchPlanFoundationTest,InstantMatchExecutorTest,LineupAdapterTest,MatchAppearanceTest,MatchTimelineValidatorTest,KnockoutPlanSplitTest,MatchPlanPersistenceTest,MatchPlanIdempotencyTest,MatchPlanRollbackTest,MatchPlanConcurrencyTest,MatchPlanReloadTest,LiveMatchCommitRescoreTest,LiveMatchSimulationServiceTest'`
  → **Tests run: 70, Failures: 0, Errors: 0**.
- Full backend suite: last run by CODEX at **356, 0, 0**; not re-run here.

### Known gaps

- `resolveKnockoutMatch`'s `et1/et2` population (private) is covered by inspection +
  plan/split tests, not a direct unit test.
- Exactly-11-starter validation assumes squads can always field 11; revisit at flag enable.
- Batch-loop ET split covered via `KnockoutPlanSplitTest` + plan tests, no flag-on knockout IT yet.

### Requested next step

The saved-lineup adapter slice (see "Next step after approval").

## Codex review result

**Revision 3 APPROVED.** The cold round-trip test now clears the persistence
context, reloads every canonical artifact, executes the persisted slots, and
compares the complete ordered goal/assist identity. No actionable findings remain
for this slice.

CODEX verification:

- `mvn test -Dtest=MatchPlanReloadTest`: **1 test, 0 failures, 0 errors**.
- `mvn test`: **356 tests, 0 failures, 0 errors**.
- `git diff --check`: clean.

Ownership returns to Claude for the saved-lineup adapter slice below.

## Next step after approval

Update `LineupAdapter` so a user-controlled team uses the actually saved XI and
bench. Only AI instant simulation may pre-plan deterministic substitutions.

Acceptance criteria for this slice:

1. Make the lineup source/mode explicit in the adapter API; do not infer "human"
   merely from the existence of a `PersonalizedTactic`, because admin-edited AI
   teams may also have one.
2. USER_SAVED uses the exact valid `first11` entries: slots below 30 are starters,
   slots 30-36 are the saved bench, preserving canonical slot order. It must not
   call `getBestEleven` when a complete valid saved XI exists.
3. USER_SAVED creates no invented substitutions. AI_INSTANT keeps deterministic
   pre-planned substitutions.
4. Validate uniqueness, team membership and exactly 11 valid starters before
   accepting saved data. Malformed/incomplete/stale saved data falls back safely
   to the existing automatic selection and records the fallback in the returned
   source/result (or another testable signal); it must not create a partial plan.
5. AI substitution pairing must exclude a goalkeeper from the incoming outfield
   pool and prefer compatible base-position groups; never replace an outfielder
   with a goalkeeper.
6. Add tests where the saved XI deliberately differs from `getBestEleven`, where
   a star is deliberately on the saved bench, USER_SAVED has zero invented subs,
   AI_INSTANT is deterministic, and malformed saved JSON falls back safely.

Return `Owner: CODEX`, `Status: REVIEW_REQUESTED` after this coherent slice and
its explicit test command pass. Do not start live-session wiring in the same slice.
