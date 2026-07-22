# Session handoff — MatchPlan refactor + Codex review loop

Continuation notes for picking this up in a fresh Claude window. The repo is
`/Users/ciprian.amza/IdeaProjects/footballmanager-backend-` (Java + Spring Boot,
H2, JPA/Hibernate, Lombok; build with `mvn` directly, no wrapper).

## TL;DR — where we are right now

- We are refactoring the match engine so a **single canonical `MatchPlan`** drives
  both the live and instant/batch paths (one source of truth for score, scorers,
  minutes, appearances). Full design: [`MATCH_PLAN_REFACTOR.md`](MATCH_PLAN_REFACTOR.md).
- Work is coordinated with **Codex** through [`AI_HANDOFF.md`](AI_HANDOFF.md) (a
  turn-based protocol). **Claude implements, Codex reviews.**
- **Current state:** Revision 4 is **APPROVED**. `AI_HANDOFF.md` now assigns Claude
  Revision 5: the first canonical LIVE-integration slice. Read its acceptance
  criteria, set `Revision: 5` / `Status: IMPLEMENTING`, and keep the flag off.
- Everything is **green**: matchplan+live explicit list = **75 tests, 0F/0E**;
  full backend `mvn test` = **361 tests, 0F/0E**. `git diff --check` clean.
- The feature flag `match.engine.matchPlan.enabled` is **OFF** by default — the
  legacy path is unchanged; the canonical path only runs when the flag is on.

## The collaboration protocol (read `AI_HANDOFF.md` first)

- Only the current **Owner** edits production code.
- Claude finishes a coherent slice → writes the "Claude implementation report" →
  sets `Owner: CODEX`, `Status: REVIEW_REQUESTED`.
- Codex reviews the real diff + tests → sets `CHANGES_REQUESTED` (ownership returns
  to Claude, with a numbered list under "Current Codex review" / "Codex review
  result") or `APPROVED`.
- On `APPROVED`, Claude does the "Next step after approval", then requests review
  the same way.

### Heartbeat loop (currently STOPPED)

We ran a `/loop 3m` cron that polled `AI_HANDOFF.md` every 3 minutes and acted on
the Control block. It was stopped on request. To resume it in the new window, run:

```
/loop 3m Check AI_HANDOFF.md Control block. If Owner is CODEX (REVIEW_REQUESTED), do nothing and keep waiting. If Owner is CLAUDE with Status CHANGES_REQUESTED, implement the items Codex listed, run the matchplan+live tests, update the Claude implementation report, then set Owner: CODEX / Status: REVIEW_REQUESTED. If Status is APPROVED, implement the "Next step after approval", request review the same way. If Status is COMPLETED, stop the loop.
```

**Important gotcha:** when Codex edits `AI_HANDOFF.md` externally (or via git), the
Read tool may report "file unchanged" from a stale cache. **Always read the file
with `cat` (Bash), not the Read tool**, to get the true current state.

## MatchPlan refactor — status

**Done and reviewed (committed up to `3b59ad5`, Revision 3 APPROVED):**
- Canonical model + persistence: `MatchPlan`, `GoalSlot`, `MatchParticipant`,
  `MatchSubstitution`, `MatchAppearance` (derived projection), all in package
  `com.footballmanagergamesimulator.matchplan`.
- `ContributionResolver` — single config-driven scorer/assist picker. Scorer =
  `position × Finishing × rating²/70`; assist = `position × passing/vision`;
  finishing/creativity bounded to [0.80,1.20] via `PositionScoringWeights.attributeMultiplier`.
- `MatchPlanningService` — score → plan (minutes, ET slots 91-120, shootout separate).
  `ALGORITHM_VERSION = matchplan-2`.
- `InstantMatchExecutor` — executes a plan into canonical `MatchEvent`s; per-slot RNG
  keyed by `seed*SALT + slotIndex`.
- `MatchPlanService.buildAndPersist` — idempotent + transactional (plan+events+timeline
  atomic); reuse by plan status; **COMMITTED is immutable**; stale-version regeneration;
  pessimistic fixture lock for concurrency; `markCommitted(fixtureKey)` called from
  `MatchRoundSimulator` after result+Scorer+stats.
- ET/shootout split via `KnockoutPlanSplit` + `KnockoutMatchResolution.et1/et2`.
- Canonical appearance timeline (starters/bench/subs/minutes played); `SubMove.sequence`
  is the single ordering rule (consecutive per team, nondecreasing minutes).
- Strict `MatchTimelineValidator` (11 starters, bench dedup, XI/bench disjoint, sub-on
  from bench, no re-entry, valid minutes, consecutive sequence).
- Cold round-trip test proving reload reproduces the exact ordered events.

**Revision 4 (APPROVED):**
- `LineupAdapter` with explicit `Mode {USER_SAVED, AI_INSTANT}` → `Result(Lineup, Source)`.
  USER_SAVED uses the manager's saved `first11` (no invented subs); AI_INSTANT keeps
  deterministic GK-safe subs. Mode is correctly decided from `UserContext.isHumanTeam`,
  not `PersonalizedTactic`.
- Validation is atomic across XI + bench; null/range/slot/id/player/team/retirement
  rules and fielded-position snapshots are covered by focused tests.

**Remaining roadmap (Codex's order, after the adapter slice):**
1. **LIVE integration** — `LiveMatchSession` loads the plan and applies the user's
   real substitutions over the canonical timeline. (Explicitly the requested next step.)
2. AI/fast-forward executor on the same plan.
3. `Scorer` + assists + minutes played **exclusively** from canonical events.
4. Cosmetic events (misses/saves/cards) layered over the plan.
5. E2E tests `instant == live`, refresh/retry, knockout.
6. Gradual enablement of `match.engine.matchPlan.enabled`.

## Key test command (Surefire 2.22.2 — the package wildcard does NOT work)

```
mvn test -Dtest='LineupAdapterTest,MatchPlanFoundationTest,InstantMatchExecutorTest,MatchAppearanceTest,MatchTimelineValidatorTest,KnockoutPlanSplitTest,MatchPlanPersistenceTest,MatchPlanIdempotencyTest,MatchPlanRollbackTest,MatchPlanConcurrencyTest,MatchPlanReloadTest,LiveMatchCommitRescoreTest,LiveMatchSimulationServiceTest'
```

Full backend: `mvn test`. Compile-only: `mvn -q test-compile`.

## Working preferences (must follow)

- Do not commit an unapproved implementation slice. After Codex approval, follow the
  user's standing instruction to commit and push every completed modification.
- **Only edit production code when Owner is CLAUDE** in `AI_HANDOFF.md`.
- Production must use the tuned, config-driven engine — no divergent hardcoded copies.
- Keep replies short; lead with the answer; tables/bullets over prose (user writes Romanian).
- H2 reserved words: a column named `minute` fails — use `@Column(name="...")` (e.g.
  `goal_minute`, `sub_minute`). `@UniqueConstraint` columnNames must match the logical
  column name, so give referenced fields an explicit `@Column(name=...)`.
- `@DataJpaTest` beans wired via `@Import` are not transactionally proxied the way you'd
  expect; for real rollback tests use `TestTransaction.end()` + a `TransactionTemplate`.

## Memory

Persistent project memory lives at
`/Users/ciprian.amza/.claude/projects/-Users-ciprian-amza-IdeaProjects-footballmanager-backend-/memory/`
— see `project_matchplan_refactor.md` and `MEMORY.md`.
