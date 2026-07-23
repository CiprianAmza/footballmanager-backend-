# Press Conference V2 — Audit & Design Contract (MEDIA)

Branch: `claude/press-conference-v2` · Worktree: `/Users/ciprian.amza/IdeaProjects/fm-media-backend`
Base: `origin/master@d006ffe`. Feature flag: **default OFF**.

## 1. Legacy implementation — as-is

### Entity: `PressConference` (`model/PressConference.java`)
Flat, single-row-per-conference. Fields: `id, teamId, seasonNumber, day, matchDay, topic, responseChosen, moraleEffect, reputationEffect`.
- **Questions are not persisted individually.** They are packed into `topic` as a delimited string:
  - `PRE_MATCH:q1|q2|q3|q4`
  - `POST_MATCH:WIN|q1|q2|...` (outcome baked into the string)
  - `BOARDROOM:|coachQuestion|ownerQuestion`
- `responseChosen == null` means PENDING. **One response per whole conference**, not per question.
- No seed, no generatorVersion, no context snapshot, no opponent/competition/fixture, no ordering, no delegation record.

### Service: `PressConferenceService`
- `generatePreMatchPressConference` / `generatePostMatchPressConference` / `generateBoardroomPressConference` — build the packed `topic`, save, and push a `ManagerInbox` row.
- Question pools are **static hardcoded `List.of(...)`** (4 pre-match, 4 per outcome post-match). No context, no determinism, no seed.
- `respondToPressConference(id, responseType)` — routes BOARDROOM topics to `respondToBoardroom`, otherwise applies a flat morale bump to all players and stores a single reputation-if-win number.

### THE RESPONSE-CODE INCOMPATIBILITY (task item)
- Legacy pre/post modal sends: **`confident` / `cautious` / `aggressive` / `deflect`**.
- Boardroom expects: **`resent` / `accept` / `assertive` / `humble`**.
- `respondToPressConference` dispatches on `topic.startsWith("BOARDROOM")`. If a client sends a boardroom code to a non-boardroom PC (or vice-versa) the `switch` `default` throws `RuntimeException("Invalid response type")`. The two code vocabularies are disjoint and only kept apart by the topic-prefix branch — brittle, and there is no shared contract.

### Controller wiring
- `GameController` `POST /pressConference/{id}/respond` body `{responseType}` → service, then auto-unpause + return game state.
- `MatchController` (live commit, ~line 700) generates post-match + boardroom PCs when the human manager `viewFullMatch && attendPressConferences`.
- `CalendarEventDispatcher` `PRESS_CONFERENCE` case (~line 151) generates the pre-match PC; already honours delegation: skips when `!attendPressConferences || isAlwaysContinue`.
- `ManagerController` exposes the `attendPressConferences` manager toggle.

### Save/load
- `GameController.gameSave()` puts `pressConferences = repo.findAll()`.
- `GameSaveImportService` manifest: `new TableSpec("pressConferences", "PRESS_CONFERENCE")` (unversioned = legacy/all-version table).
- Versions: `LEGACY=5, V6=6, V7=7, CURRENT=8`. New tables must be added as `TableSpec(..., SAVE_VERSION_9)` after bumping `CURRENT_SAVE_VERSION`.

### Migrations / DB
- Active DB = **H2**; Flyway `locations: classpath:db/migration/h2`, `ddl-auto: update` (Hibernate also creates tables from entities).
- `db/migration/{h2,postgresql,mysql}`. H2 has V1–V3; PG & MySQL have only V1.
- Cross-DB contract enforced by `FlywayPhase0CrossDatabaseIT` (Testcontainers pg16 + mysql8) + `PostgreSqlGameSaveIT` / `MySqlGameSaveIT`.

### Effect homes that already exist (no new column strictly required)
- Player/squad morale: `Human.morale` (double 0–100).
- Owner: `Human.ownerArrogance`. Coach: `Human.coachHumiliation`.
- Manager reputation: `Human.managerReputation` (int, default 500). Club reputation: `Team.reputation` (int).
- Board dynamics via `CoachPermissionService` (offToggles / lockedSlots) + `MatchEngineConfig.Boardroom`.

## 2. V2 target design (recommended)

New tables (all under save `SAVE_VERSION_9`, additive; legacy `press_conference` kept intact):
- `press_conference_session` — type (PRE_MATCH/POST_MATCH), status (PENDING/IN_PROGRESS/COMPLETED/DELEGATED), fixtureKey, teamId, opponentId, competitionId, season, day, seed, generatorVersion, currentQuestionIndex, contextSnapshot (JSON, immutable), createdAt/completedAt/delegatedAt/delegatedBy.
- `press_conference_question` — sessionId, orderIndex, catalogQuestionId, contextKey, promptText (frozen copy), answeredAnswerId (nullable).
- `press_conference_answer` — questionId, catalogAnswerId, answerCode, tone, stance, appliedEffectsJson, appliedAt. One row per answered question (idempotent by (questionId)).

Catalog: versioned JSON at `src/main/resources/press/press-conference-catalog-v1.json`, loaded once at startup, keyed by `generatorVersion`. Each question: `id, contextKey, eligibility, weight, prompt, answers[]`; each answer: `id, code, tone, stance, eligibility, weight, effects[]` where an effect is `{target, metric, value}` with `target ∈ {MANAGER, PLAYER, SQUAD, BOARD, OWNER, MEDIA}`.

Determinism: `seed = stableHash(generatorVersion, type, fixtureKey, teamId, opponentId, season, day)`. A seeded RNG selects 3–6 eligible, non-duplicate questions (dedupe by `contextKey`) and freezes their prompt + answer variants at creation. Refresh/restart reads persisted rows — never regenerates. `Start` is idempotent (retry returns the same session via a unique key on (teamId, type, fixtureKey, season)).

Legacy compatibility: keep `/pressConference/{id}/respond` and its `confident/cautious/aggressive/deflect` + boardroom codes working unchanged when flag OFF. Introduce a shared `ResponseStance` mapping so legacy codes resolve to the same stance vocabulary the catalog uses; V2 endpoints address answers by `answerId`.

Delegation: assistant delegate → status `DELEGATED`; assistant auto-answers deterministically (seed-driven pick of the safest/neutral eligible answer per question) with damped effects; completed/delegated sessions are immutable (further answer calls are no-ops / idempotent).

## 3. Initial questions (asked once — see MEDIA.md). Recommended defaults in **bold**; adopted at next heartbeat if no answer.

1. **Save version** — bump `CURRENT_SAVE_VERSION` 8→9 and register the 3 new tables at `SAVE_VERSION_9`, keeping legacy `press_conference`? **Yes.**
2. **Feature flag** — add `press-conference-v2.enabled: false` (mirrors `regent.enabled`)? New generator/endpoints gated; legacy path untouched when OFF. **Yes, this name.**
3. **Media reputation target** — reuse `Human.managerReputation` + `Team.reputation` for MEDIA-target effects (no new column)? **Yes, reuse.**
4. **Delegation effects** — DELEGATED session applies damped (×0.5) deterministic auto-answers vs. zero effect? **Damped auto-answers.**
5. **Catalog format/location** — JSON at `resources/press/press-conference-catalog-v1.json`? **Yes (JSON).**
6. **Legacy codes** — keep `confident/cautious/aggressive/deflect` + boardroom codes valid via a stance-mapping shim rather than deleting them? **Yes, shim + keep.**
7. **Cross-DB parity** — add V4 to h2 **and** mirror to postgresql/mysql dirs for the contract ITs (even though only H2 is the active runtime datasource)? **Yes, mirror all three.**
