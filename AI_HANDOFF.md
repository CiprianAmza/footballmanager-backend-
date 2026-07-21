# AI implementation and review handoff

This file coordinates implementation work between Claude and Codex. The Git diff
and executable tests remain the source of truth; summaries in this file are only
the handoff protocol.

## Control

- Revision: 1
- Owner: CLAUDE
- Status: CHANGES_REQUESTED
- Base commit: `61de820`
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

Claude should replace this section when requesting review, including:

- files changed;
- behavioral decisions;
- migrations/schema effects;
- exact test commands and results;
- known gaps;
- requested next step.

## Codex review result

Pending the changes listed above.

## Next step after approval

Update `LineupAdapter` so a user-controlled team uses the actually saved XI and
bench. Only AI instant simulation may pre-plan deterministic substitutions.
