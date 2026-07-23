# Football Data Analytics — gap analysis and delivery plan

## Document control

- Analysis baseline: backend commit `49664fb2d56f6e6b99e25abfe882f88d4c1df392`.
- Frontend baseline (read-only): `/Users/ciprian.amza/Downloads/footballmanager-frontend-test`, commit `2f9b08429c1ed5c08d996c73d795537acaedf79b`.
- Audit treated as a hypothesis set: `/Users/ciprian.amza/IdeaProjects/footballmanager-backend-/FOOTBALL_DATA_ANALYTICS_AUDIT.md` (2026-07-22), not as current truth.
- Pending work inspected only for overlap, never treated as canonical: TITAN `51806fc78f1c554277070184ce6b0a55bb4ea2a0`; REGENT P2 backend `9677abfb1c8f40dc2359d991be197b28653e20af`; REGENT P2 frontend `31e452885874f6ce899d7ecf8bf8412e598feae5`.
- This is a technical/product decision package. It intentionally contains no final implementation prompt and makes no production, test, configuration, migration, frontend, merge, flag or rollout change.
- Reference convention: every `file:line` basename cited below is unique at its pinned SHA. Backend production files resolve under `src/main/java/com/footballmanagergamesimulator/` (or `src/main/resources/`), backend tests under `src/test/java/com/footballmanagergamesimulator/`, and frontend files under `/Users/ciprian.amza/Downloads/footballmanager-frontend-test/src/app/`. Pending evidence uses `commit:path:line` and is explicitly non-canonical.

## 1. Executive summary

The product already has more canonical match infrastructure than the audit records. At the pinned backend SHA, a durable `MatchPlan` stores a stable fixture identity, seed, version, 90-minute/extra-time/shootout split and immutable commit status; participants, substitutions and derived appearances are persisted beside it. Human instant, interactive live, AI-vs-AI, Fast Forward and knockout routes can all consume that structure. The AI fast path, live manual-substitution commit semantics, retry/concurrency protection and save/load coverage were added or corrected since the audit. These capabilities are real but remain behind `match.engine.match-plan.enabled=false`, so the default runtime still uses legacy paths (`MatchPlan.java:8-24`, `MatchRoundSimulator.java:363-378`, `MatchRoundSimulator.java:695-729`, `MatchdayCoordinator.java:459-491`, `MatchEngineConfig.java:984-993`).

That infrastructure is not yet a football-data ledger. `MatchPlan` starts from an already-decided score and its canonical event projection contains goals and assists only. `MatchStats` then generates shots, xG, possession, passes, duels, defensive work and goalkeeper saves after the score; live mode observes a reduced set of counters but fills the remainder with a second synthetic generator. Player analytics assign each starter 90 minutes and attribute-derived tallies; the field named `shots` actually stores the synthetic `Expected Goals` metric and is later displayed as “Most shots” (`MatchPlanService.java:79-118`, `MatchStatsService.java:47-62`, `MatchStatsService.java:112-141`, `MatchStatsService.java:256-365`, `PlayerMatchStatService.java:108-146`, `StatsAggregationService.java:158-169`).

The recommended MVP is therefore not a second full simulation engine and not a catalogue of 100 vendor metrics. It is a **reduced canonical fact ledger** built on the existing MatchPlan transaction boundary:

1. make fixture identity, provenance and mode parity explicit while the flag stays OFF;
2. add a compact shot/outcome ledger and derive score, xG and saves from it;
3. derive real player minutes and a deliberately small player-stat projection from appearances and core events;
4. expose observed/reduced/estimated/legacy provenance in API v2 and the existing frontend;
5. keep possession, passes, duels and physical load labelled `ESTIMATED` until a separately justified engine phase exists.

This yields one auditable truth for score, goals, shots, xG, saves, cards, substitutions and minutes without paying the CPU/storage cost of a vendor-like pass/tracking feed. The Compartment Engine, if later approved, belongs upstream: it supplies team strength, tactical matchup and chance priors. The analytics ledger owns sampled match facts and reducers. Animation V3 remains downstream presentation and must never become a statistical source.

## 2. Classification vocabulary

The audit-to-code matrix uses only these states:

- `PRESENT_AND_CANONICAL`: present at the pinned backend/frontend SHA and active as the canonical contract, with no default-off feature gate required for the stated capability.
- `PRESENT_BEHIND_FLAG`: implemented at the pinned SHA but reachable as the stated canonical capability only when a default-off flag is enabled.
- `PARTIAL_OR_INCONSISTENT`: some representation exists, but semantics, identity, mode coverage, provenance or reduction are inconsistent.
- `SUPERSEDED_OR_FIXED_SINCE_AUDIT`: the audit statement is stale at the pinned SHA; any remaining limitation is stated separately.
- `ABSENT`: no persisted/current contract implements the capability.
- `OUT_OF_SCOPE_OR_NOT_WORTH_IT`: technically possible but deliberately excluded from the recommended near-term product.

“Canonical” in this vocabulary means “the repository's authoritative implementation/contract”, not “professionally observed football data”. A synthetic field can be canonical application state and still be analytically estimated.

## 3. Explicit corrections to the stale audit

1. **AI-vs-AI does have a canonical MatchPlan path now.** With the MatchPlan flag ON it builds cached deterministic lineups, resolves canonical slots, projects `Scorer` from those events and commits only after batched effects (`MatchRoundSimulator.java:695-729`, `MatchRoundSimulator.java:864-892`). The flag-OFF legacy path still exists.
2. **The critical canonical live manual-substitution re-score mismatch is fixed.** `resolveCommitOutcome` still computes a legacy re-score, but the canonical coordinator explicitly keeps `session.getHomeScore()/getAwayScore()` and ignores the re-sampled goals (`LiveMatchSimulationService.java:1086-1122`, `MatchdayCoordinator.java:475-491`). That unnecessary calculation and its aptitude loss are cleanup items, not the audit's P0 score/event corruption.
3. **Canonical interactive knockout state is pre-decided before kickoff.** Extra time and shootout are separated, and shootout values do not become goal slots (`MatchRoundSimulator.java:473-493`, `MatchRoundSimulator.java:668-689`).
4. **Fast Forward is not a separate score implementation.** It loops `GameAdvanceService.advance`, which dispatches normal matchdays to `MatchRoundSimulator`; therefore the canonical AI fast path is inherited when its flag is ON (`FastForwardService.java:120-154`, `GameAdvanceService.java:23-29`, `MatchdayCoordinator.java:167-180`).
5. **Save/load is not v5 and does not omit MatchPlan state.** The current H2 manifest is v7, covers MatchPlan, goal slots, participants, appearances, substitutions, animation recipes and live recovery context, rejects undisposed persisted tables and has cross-instance recovery coverage (`GameSaveImportService.java:42-44`, `GameSaveImportService.java:70-84`, `GameSaveImportService.java:690-700`, `GameSaveCrossInstanceIT.java:50-119`).
6. **A real Angular frontend exists at the supplied canonical SHA.** It consumes match summary, event, team-stat and player-analytics endpoints and supports live resume/advance/commit/substitution (`fixtures.component.ts:503-577`, `player-analytics.component.ts:26-32`, `app.component.ts:423-447`, `app.component.ts:1192-1209`, `app.component.ts:1248-1264`, `app.component.ts:1677-1701`). The remaining issue is missing data provenance, not missing UI.
7. **Animation V3 is integrated, but presentation-only and default OFF.** It consumes already-decided canonical goal facts and falls back without changing those facts (`AnimationV3GoalAdapter.java:22-33`, `AnimationV3GoalAdapter.java:51-95`, `application.yml:98-104`). It neither closes nor expands the analytics ledger gap.

## 4. Exhaustive audit-to-code matrix

| Audit capability or finding | Classification | Current code evidence and precise disposition |
|---|---|---|
| Scalar/tactical score is decided before detailed match facts | `PRESENT_AND_CANONICAL` | `MatchSimulationService.java:82-91` samples the score from effective powers; `TacticalScoreService.java:228-234` samples ET. This is current score causality, not a desired analytics feature. |
| One durable fixture plan with seed/version/result split/status | `PRESENT_BEHIND_FLAG` | `MatchPlan.java:8-24`, `MatchPlan.java:34-56`, and `MatchPlan.java:116-118`; default gate at `MatchEngineConfig.java:984-993`. |
| Goal slots resolved once with stable scorer/assist facts | `PRESENT_BEHIND_FLAG` | `GoalSlot.java:5-17`, `GoalSlot.java:31-43`, `MatchPlanService.java:217-257`, `MatchPlanService.java:524-543`. |
| Persisted kickoff participant snapshot | `PRESENT_BEHIND_FLAG` | `MatchParticipant.java:5-17`, `MatchParticipant.java:29-52`, `MatchPlanService.java:550-560`. Snapshot is sufficient for contributor selection, not a complete analytics manifest/config fingerprint. |
| Canonical substitution timeline and derived appearances/minutes | `PRESENT_BEHIND_FLAG` | `MatchSubstitution.java:5-13`, `MatchAppearance.java:5-16`, `MatchAppearance.java:32-61`, `MatchPlanService.java:562-576`. |
| Score equals canonical goal-event count; scorers are on pitch | `PRESENT_BEHIND_FLAG` | Runtime projection at `MatchRoundSimulator.java:556-595` and `MatchRoundSimulator.java:695-723`; assertions at `CanonicalAiFastPathIT.java:105-183`. |
| Canonical live goal display equals durable goal events | `PRESENT_BEHIND_FLAG` | Live emits due slots at `LiveMatchSession.java:1090-1103`; canonical commit never rebuilds goals at `MatchdayCoordinator.java:653-682`; parity test at `CanonicalInstantLiveE2ETest.java:112-166`. |
| Audit P0: a manual live substitution re-scores the canonical match at commit | `SUPERSEDED_OR_FIXED_SINCE_AUDIT` | Canonical commit pins session score at `MatchdayCoordinator.java:475-491`. `LiveMatchSimulationService.java:1086-1122` still calculates a discarded re-score and should be simplified. |
| Audit: optimized AI path has no MatchPlan/events | `SUPERSEDED_OR_FIXED_SINCE_AUDIT` | Canonical AI path added at `MatchRoundSimulator.java:695-729`; default flag remains OFF and flag-off test intentionally retains no plan/events (`CanonicalAiFastPathFlagOffIT.java:25-35`, `CanonicalAiFastPathFlagOffIT.java:52-77`). |
| Audit: Fast Forward necessarily differs from canonical human execution | `SUPERSEDED_OR_FIXED_SINCE_AUDIT` | Fast Forward uses ordinary advance at `FastForwardService.java:120-154`; ordinary matchdays reach `MatchRoundSimulator` at `MatchdayCoordinator.java:167-180`. Full parity still depends on the default-off MatchPlan gate. |
| Retry/idempotency for a committed match and every side effect | `PRESENT_BEHIND_FLAG` | Pre-RNG fixture locking/no-op at `MatchRoundSimulator.java:363-378`; idempotency coverage at `CanonicalAiFastPathIT.java:185-192` and `MatchPlanIdempotencyTest.java:78-128`. |
| Concurrent round/live commit has one winner and one no-op loser | `PRESENT_BEHIND_FLAG` | Round locking at `MatchRoundSimulator.java:363-378`; live transaction/lock at `MatchdayCoordinator.java:418-473`; tests at `CanonicalAiFastPathIT.java:226-229` and `MatchdayCoordinatorCanonicalCommitConcurrencyTest.java:93-166`. |
| Knockout 90'/ET/shootout separation | `PRESENT_BEHIND_FLAG` | `MatchRoundSimulator.java:532-553`, `MatchRoundSimulator.java:668-689`; `MatchPlan.java:34-56` keeps shootout separate. |
| MatchEvent is a general causal event ledger | `PARTIAL_OR_INCONSISTENT` | `MatchEvent.java:19-46` has a stable identity only for canonical goal slots and a minimal payload; no second/period, actor-opponent relation, coordinates, outcome enum, related event, xG or model version. |
| Live mode observes a reduced action stream | `PARTIAL_OR_INCONSISTENT` | Shots/saves/misses are generated per minute and saved at `LiveMatchSession.java:1193-1295`; blocks/corners are timeline-only at `LiveMatchSession.java:1308-1320`; cards persist but ordinary fouls do not at `LiveMatchSession.java:1360-1406`; offsides are timeline-only at `LiveMatchSession.java:1409-1422`. |
| Team match-stat persistence and API | `PRESENT_AND_CANONICAL` | Aggregate row shape at `MatchStats.java:8-105`; endpoint at `MatchController.java:825-907`. The row is canonical application data, but most values below are estimated. |
| Team possession and pass counts are derived from possessions/passes | `PARTIAL_OR_INCONSISTENT` | Instant path samples both from power/tactics at `MatchStatsService.java:83-110`; live commit invents passes/accuracy after play at `MatchStatsService.java:291-302`; no pass event row exists. |
| Weighted pass accuracy across a season | `PARTIAL_OR_INCONSISTENT` | Season API averages match percentages rather than completed/attempted totals (`MatchStatsService.java:392-446`). |
| Shots and shots on target have one persisted row per attempt | `ABSENT` | Only aggregate columns exist (`MatchStats.java:35-41`); `MatchEvent.java:34-46` has no shot outcome/xG schema. Live saves/misses are sparse events but instant/AI have none. |
| Shot xG is pre-outcome and sum of persisted attempts | `ABSENT` | Aggregate chance sets are sampled conditional on the already-known goal count (`MatchStatsService.java:487-541`, `MatchStatsService.java:558-570`); no shot entity stores pre-shot features/version. |
| Aggregate shots/xG remain numerically coherent after a decided score | `PRESENT_AND_CANONICAL` | Current generator enforces shots/SOT ≥ goals and distribution bands (`MatchStatsService.java:125-140`, `MatchStatsServiceTest.java:24-117`). This is conditional narrative consistency, not causal xG. |
| Big chances are linked to persisted shots | `PARTIAL_OR_INCONSISTENT` | Big-chance flags exist only inside the transient score-conditioned chance sample and become aggregate counts (`MatchStatsService.java:493-541`); there is no attempt identity to inspect or rebuild. |
| Crosses, corners and offsides have individual action facts in every mode | `PARTIAL_OR_INCONSISTENT` | Instant/AI crosses/corners/offsides are sampled aggregates (`MatchStatsService.java:143-178`, `MatchStatsService.java:209-219`); live corners and offsides are timeline-only (`LiveMatchSession.java:1315-1320`, `LiveMatchSession.java:1409-1422`). |
| Duels, tackles, interceptions, clearances and aerials are action-derived | `PARTIAL_OR_INCONSISTENT` | All are sampled aggregates at `MatchStatsService.java:180-233`; live mode samples them after the match at `MatchStatsService.java:320-363`. Aerial wins are not complementary contest outcomes. |
| Fouls/cards have offender, victim, reason and causal chain | `PARTIAL_OR_INCONSISTENT` | Live cards identify one player, but ordinary fouls are not persisted and there is no victim/restart link (`LiveMatchSession.java:1360-1406`, `MatchEvent.java:39-46`). Batch values are sampled aggregates (`MatchStatsService.java:149-172`). |
| Saves are linked to eligible shots and goalkeeper appearances | `PARTIAL_OR_INCONSISTENT` | Saves are computed as opponent SOT minus goals (`MatchStatsService.java:198-207`, `MatchStatsService.java:332-347`); `MatchStats` stores team totals only (`MatchStats.java:73-85`). |
| Player match statistics are event/minute-derived | `PARTIAL_OR_INCONSISTENT` | Every selected starter gets 90 and deterministic attribute formulas (`PlayerMatchStatService.java:108-146`); AI input is cached starting XI only (`MatchRoundSimulator.java:743-746`). |
| `PlayerSeasonStat.shots` means shots | `PARTIAL_OR_INCONSISTENT` | Writer stores synthetic `Expected Goals` in `shots` (`PlayerMatchStatService.java:126-142`); reader maps it back to `Expected Goals` (`PlayerAnalyticsService.java:175-196`); leaderboard displays it as “Most shots” (`StatsAggregationService.java:158-169`). |
| Per-player pass attempts/completions are observed | `PARTIAL_OR_INCONSISTENT` | Attempt volume is derived from synthetic pass percentage, then completion applies the same percentage again (`PlayerMatchStatService.java:131-144`). |
| Player heatmap represents observed positions/touches | `PARTIAL_OR_INCONSISTENT` | It is a position template modulated by attributes (`PlayerAnalyticsService.java:299-337`); frontend comments correctly call the feature synthetic (`player-analytics.component.ts:26-32`). API lacks machine-readable provenance. |
| Performance ratings are action-value ratings | `PARTIAL_OR_INCONSISTENT` | `Scorer` rating is assigned from result/goals/assists/clean sheet/noise (`MatchSimulationService.java:343-377`); `MatchPlayerRating` separately stores lineup strength and performance rating (`MatchPlayerRating.java:19-45`). |
| Stable fixture identity across MatchStats, Scorer and MatchPlayerRating | `PARTIAL_OR_INCONSISTENT` | `MatchStats` uniqueness is competition/season/round/ordered teams (`MatchStats.java:8-25`); `Scorer.java:8-43` and `MatchPlayerRating.java:8-45` have no canonical fixture key. MatchPlan itself does. |
| MOTM stored on MatchStats | `PARTIAL_OR_INCONSISTENT` | Fields exist at `MatchStats.java:99-105`, but summary computes highest `Scorer.rating` instead (`MatchController.java:440-453`). |
| Awards consume definitionally sound player facts | `PARTIAL_OR_INCONSISTENT` | Player of Year double-weights contribution on top of result-based rating (`AwardService.java:515-539`); Most Entertaining consumes attribute-derived chances/dribbles (`AwardService.java:590-600`, `PlayerMatchStatService.java:188-215`); Best Goalkeeper joins sampled team saves by round/team and combines them with clean sheets/rating (`AwardService.java:602-642`). Golden Boot's goal-first competition ranking is reasonable once goal projection is canonical (`AwardService.java:541-553`). |
| API exposes source/fidelity/model-version/reconciliation state | `ABSENT` | Stats response exports labels/raw values only (`MatchController.java:841-905`); events return entities directly (`MatchController.java:206-218`); analytics DTO selection exposes accumulated/sample counts but no source version (`PlayerAnalyticsService.java:68-108`). |
| Match summary never silently substitutes an estimate | `PARTIAL_OR_INCONSISTENT` | When `MatchStats` is absent, summary estimates possession from current team power without source metadata (`MatchController.java:455-473`). |
| Frontend has match/team/player analytics consumers | `SUPERSEDED_OR_FIXED_SINCE_AUDIT` | `fixtures.component.ts:503-577`, `data-hub.component.ts:433-446`, `player/player.component.html:485-490`, `competition.component.ts:150-183`. |
| Frontend distinguishes observed/reduced/estimated/legacy | `ABSENT` | Existing screens render xG, possession, passes and synthetic player analytics as ordinary metrics (`data-hub.component.html:155-218`, `competition.component.ts:159-183`). Comments are not a user/API contract. |
| Live per-minute stamina and real live minutes | `PRESENT_AND_CANONICAL` | Per-minute state and tempo/attributes at `LiveMatchSimulationService.java:503-535`; write-back uses only participants and actual burn at `LiveMatchSimulationService.java:797-823`. This state is not the season player-stat source. |
| Instant/AI fatigue and minutes match live semantics | `PARTIAL_OR_INCONSISTENT` | Batch subtracts a fixed configured drain from cached starters (`MatchRoundSimulator.java:980-1009`), while player stats award 90 (`PlayerMatchStatService.java:122-146`). |
| Distance, high-intensity running, sprint, acceleration/deceleration facts | `ABSENT` | `MatchStats.java:21-105` and `PlayerSeasonStat.java:35-66` contain no physical fields; current physical state stops at stamina/minutes (`LiveMatchSimulationService.java:827-835`). |
| Availability/injury/card context is active during long simulation | `PARTIAL_OR_INCONSISTENT` | The pinned production default disables player availability (`application.yml:66-69`), so analytics/calibration runs must record that context instead of comparing enabled/disabled worlds as one population. |
| Save/load contains canonical plan/live recovery state | `SUPERSEDED_OR_FIXED_SINCE_AUDIT` | Manifest entries at `GameSaveImportService.java:70-84`; exhaustive schema disposition at `GameSaveManifestCoverageTest.java:27-42`; cross-instance semantic equality at `GameSaveCrossInstanceIT.java:50-119`. |
| Save/load will automatically include future analytics tables | `ABSENT` | The manifest rejects any undisposed persisted table (`GameSaveImportService.java:690-700`); every new ledger/reducer table requires a save-version and coverage update. |
| Schema evolution has reviewed migrations for all current gameplay analytics tables/dialects | `PARTIAL_OR_INCONSISTENT` | Production config uses Hibernate `ddl-auto:update` plus H2 Flyway (`application.yml:8-16`); the current migration directories do not define MatchPlan/MatchStats/player-stat schemas. New ledger work must not rely on implicit DDL. |
| Statistical calibration has deterministic tooling and hard analytics release gates | `PARTIAL_OR_INCONSISTENT` | Score-engine invariant tooling is deterministic but individual failures are informational (`EngineInvariantsCatalogIT.java:17-35`, `EngineInvariantsCatalogIT.java:60-74`); current MatchStats tests cover selected 4k/5k distributions (`MatchStatsServiceTest.java:54-117`), not a versioned end-to-end ledger calibration report. |
| Progressive passes/carries, sequences, field tilt, PPDA and possession regains | `ABSENT` | No current event schema supports start/end position, possession or linked actions (`MatchEvent.java:19-46`). These become possible only after the optional Phase 4 action engine. |
| Full pressure/tracking feed and VAEP/xT/PSxG suite | `OUT_OF_SCOPE_OR_NOT_WORTH_IT` | The inputs do not exist in the current schema (`MatchEvent.java:19-46`), and the product value does not justify fabricating or persisting a vendor-like feed in the MVP. |

## 5. Current contract, entity and persistence inventory

### 5.1 Authoritative, reduced and synthetic layers

| Layer | Current source | What it can honestly claim | What it cannot claim |
|---|---|---|---|
| Fixture result | `CompetitionTeamInfoMatch`/detail plus optional `MatchPlan` (`MatchRoundSimulator.java:778-850`, `MatchPlan.java:34-56`) | Persisted score/result; under MatchPlan, 90'/ET/shootout split and idempotency | That the score emerged from recorded shots |
| Canonical goal truth | `MatchPlan` → `GoalSlot` → `MatchEvent` (`MatchPlanService.java:217-257`, `MatchEventProjection.java:8-13`, `MatchEventProjection.java:27-37`) | Goal minute, side, scorer, assist and on-pitch eligibility when flag ON | Shot origin/outcome/xG, goalkeeper, defender, possession chain |
| Canonical lineup truth | `MatchParticipant`, `MatchSubstitution`, `MatchAppearance` (`MatchParticipant.java:29-79`, `MatchAppearance.java:5-16`) | Kickoff contributor snapshot and derived minute span when flag ON | Current player-season analytics, which still reads `PlayerSeasonStat` synthetic minutes |
| Reduced live observations | `LiveMatchSession` counters/events (`LiveMatchSession.java:1131-1182`, `LiveMatchSession.java:1193-1406`) | Minute-by-minute possession side, shots, SOT, score, corners, fouls, cards, stamina in the live session | Complete persisted event coverage; parity with instant-generated aggregates |
| Team estimates | `MatchStats`/`MatchStatsService` (`MatchStats.java:21-105`, `MatchStatsService.java:47-235`) | Reproducible-looking post-match aggregate narrative and basic numeric invariants | Observed pass/duel/shot provenance or causal reconstruction |
| Player estimates | `PlayerSeasonStat`/analytics (`PlayerSeasonStat.java:6-20`, `PlayerAnalyticsService.java:68-108`) | Attribute/tactic-based comparison/gameplay narrative | Real per-action or per-minute performance |
| Presentation | Animation recipes/timeline (`AnimationDirector.java:9-45`, `MatchAnimationRecipe.java:5-35`) | Deterministic replay of already-decided canonical goals | A source for stats, locations or physical distance |

### 5.2 Domain-by-domain inventory

**MatchPlan and event ledger.** The plan has the correct transactional skeleton: stable fixture key, deterministic seed, algorithm version, status and result split (`MatchPlan.java:20-56`). Goal slots are uniquely indexed inside the plan and resolved idempotently (`GoalSlot.java:5-17`, `MatchPlanService.java:217-257`). `MatchEvent` carries plan provenance only for the goal/assist projection; legacy rows use null/-1 and the type remains a string (`MatchEvent.java:19-46`). This is a **goal ledger**, not a general event ledger.

**Score causality.** Both scalar and two-axis models produce goal totals directly from power/tactical profiles before the plan and stats (`MatchSimulationService.java:82-91`, `TacticalScoreService.java:241-299`). MatchPlan schedules exactly that decided total (`MatchPlanService.java:79-118`). Team shots/xG are then drawn conditional on goals (`MatchStatsService.java:112-141`, `MatchStatsService.java:487-570`). The existing separation is valuable for compatibility but must be labelled “score-conditioned estimate”, not event causality.

**Team statistics.** One `MatchStats` row contains score, possession, shots, set pieces, discipline, passing, defending, saves, advanced chance aggregates, crosses, duels and unused MOTM fields (`MatchStats.java:21-105`). Identity is a five-column natural key rather than fixture key (`MatchStats.java:8-14`). Season totals mix all competitions by team/season and average pass percentages unweighted (`MatchStatsService.java:379-456`).

**Player statistics and minutes.** `Scorer` provides appearance-like rows, goals, assists and a result-based rating but has no fixture key (`Scorer.java:8-43`). `MatchPlayerRating` stores a separate lineup-strength/performance view, also with no fixture key (`MatchPlayerRating.java:8-45`). `PlayerSeasonStat` is an accumulated synthetic table with indexes but no database uniqueness on `(player, competition, season)` (`PlayerSeasonStat.java:22-38`). Its writer merges duplicate candidates in memory and grants every selected starter 90 minutes (`PlayerMatchStatService.java:92-124`). By contrast, MatchPlan appearances already contain real start/exit/minutes under the flag (`MatchAppearance.java:32-61`).

**Shots and xG.** Instant/AI paths have only aggregate shot counts and aggregate xG; no attempt is persisted. Live creates identifiable saved/wide shot events, but blocked shots and corners remain timeline-only and none carries xG or defender/GK identity (`LiveMatchSession.java:1193-1320`). Current xG is Bayesian score-conditioned narrative: low-xG goals remain possible, but the already-known goal outcome participates in chance-quality sampling (`MatchStatsService.java:558-570`).

**Passes and possession.** Instant possession is a power/tactic/noise percentage, passes are scaled from that percentage and accuracy is sampled (`MatchStatsService.java:83-110`). Live possession is a real reduced count of which side had the ball each minute (`LiveMatchSession.java:1131-1135`), but the persisted live pass values are fabricated afterwards (`MatchStatsService.java:291-302`). No possession, sequence or pass entity exists.

**Duels and defense.** Tackles/interceptions/clearances depend on possession and noise; duel wins are generated totals rather than linked winner/loser contests; aerial wins are independent side samples (`MatchStatsService.java:180-233`). Live persists card events but not ordinary fouls, offsides, blocks or corners (`LiveMatchSession.java:1308-1422`).

**Goalkeeping.** Team saves equal eligible opposition SOT minus goals (`MatchStatsService.java:198-207`). There is no shot-to-GK link, save percentage reducer, penalty save fact, PSxG or keeper-distribution ledger. Clean sheet remains derivable from result and appearance.

**Physical/fatigue.** Live owns useful per-player stamina state: minutes, pace/stamina/natural-fitness/tempo modifiers and post-match write-back (`LiveMatchSimulationService.java:503-535`, `LiveMatchSimulationService.java:797-835`). Instant/AI applies fixed drain and only cached starters are treated as having played (`MatchRoundSimulator.java:980-1009`). There is no distance/sprint/HID persistence. This is a mode-parity gap, not a reason to infer GPS-like truth from animations.

**Provenance/API/frontend.** Match APIs identify fixtures using competition/season/round/team pairs and return entities or anonymous maps (`MatchController.java:206-218`, `MatchController.java:825-907`). No response says `OBSERVED_REDUCED`, `SCORE_CONDITIONED_ESTIMATE`, `ATTRIBUTE_ESTIMATE`, `LEGACY` or model version. The frontend already has screens for these values but renders them without badges (`fixtures.component.ts:503-577`, `data-hub.component.html:155-218`, `player-analytics.component.ts:26-32`).

**Save/load.** All existing analytics and MatchPlan tables are in the manifest (`GameSaveImportService.java:70-84`) and current schema coverage is exhaustive (`GameSaveManifestCoverageTest.java:27-42`). Any new table must update the manifest, export/import version behavior, generator alignment where needed and H2/cross-database tests. The pending REGENT P2 commit independently raises H2 save state to v8 and adds four market tables (`9677abfb1c8f40dc2359d991be197b28653e20af:src/main/java/com/footballmanagergamesimulator/controller/GameSaveImportService.java:43-46`, `9677abfb1c8f40dc2359d991be197b28653e20af:src/main/java/com/footballmanagergamesimulator/controller/GameSaveImportService.java:116-123`), so version numbering must be rebased after ATLAS decides canonical order.

### 5.3 Mode parity at the pinned SHA

| Mode | Flag OFF | MatchPlan flag ON | Remaining analytics difference |
|---|---|---|---|
| Human instant | Independent scorer RNG and legacy generated events (`MatchRoundSimulator.java:556-576`) | Plan/events/scorer projection and committed transaction (`MatchRoundSimulator.java:561-595`) | Team/player stats still generated after score |
| Interactive live | Legacy timeline and commit behavior | Prepared slots, real on-pitch resolution, persistent checkpoints/subs, pinned score (`LiveMatchSession.java:1090-1103`, `MatchdayCoordinator.java:653-709`) | Only a reduced set is observed; live persistence synthesizes the rest |
| AI-vs-AI | Simplified scorer rows, no MatchPlan events (`MatchRoundSimulator.java:724-729`) | Cached deterministic lineup, canonical events/scorers and transactional commit (`MatchRoundSimulator.java:695-723`, `MatchRoundSimulator.java:864-892`) | `MatchStats` receives null tactics (`MatchRoundSimulator.java:770-775`); player stats use starting XI only |
| Fast Forward | Same legacy AI/human auto-continue routes | Inherits ordinary canonical round path (`FastForwardService.java:120-154`) | Needs explicit multi-season parity/reconciliation test, not a separate engine |
| Knockout | Legacy tiebreak/event paths | 90'/ET/shootout split is persisted and shootout excluded from goal tally (`MatchRoundSimulator.java:532-553`, `MatchRoundSimulator.java:668-689`) | No shot/xG/keeper ledger for ET or penalties |

## 6. Real/reduced facts versus synthetic/display-only values

The public contract should use these explicit provenance values. “Observed” here means generated as an actual fact by this simulator, not captured from a real-world tracking provider.

| Provenance | Current examples | Required public wording |
|---|---|---|
| `CANONICAL_FACT` | Result row; under MatchPlan: goal slot, goal/assist contributor, participant, substitution, appearance (`MatchPlanService.java:217-280`, `MatchPlanService.java:550-576`) | “Match fact”; include fixture key and algorithm version |
| `OBSERVED_REDUCED` | Live per-minute possession side, shot/SOT/corner/foul/card counters and stamina (`LiveMatchSession.java:1131-1182`, `LiveMatchSession.java:1193-1406`) | “Generated by live reduced simulation”; never imply a complete action feed |
| `SCORE_CONDITIONED_ESTIMATE` | Instant/AI shots, xG, SOT, saves after goal total (`MatchStatsService.java:112-141`, `MatchStatsService.java:198-207`, `MatchStatsService.java:487-570`) | “Estimated after result”; include estimator version |
| `MATCH_CONTEXT_ESTIMATE` | Possession, passes, duels, defense and live fill-ins (`MatchStatsService.java:83-110`, `MatchStatsService.java:291-363`) | “Estimated match statistic”; do not aggregate beside observed values without source split |
| `ATTRIBUTE_ESTIMATE` | Player pressure/pass/xG formulas and heatmaps (`PlayerMatchStatService.java:126-146`, `PlayerAnalyticsService.java:299-337`) | “Attribute-based estimate”; never label `shots` when the source is expected goals |
| `DISPLAY_ONLY` | Commentary, animation frames and recipes (`AnimationV3GoalAdapter.java:22-33`, `AnimationDirector.java:35-45`) | No statistical extraction; display contract only |
| `LEGACY_UNVERSIONED` | MatchEvent rows with null fixture key and old aggregates (`MatchEvent.java:19-28`) | Historical/legacy badge; no silent upgrade to canonical provenance |

Two non-negotiable rules follow:

1. A reducer may consume `CANONICAL_FACT` or a declared reduced fact. It must never parse commentary/animation or reverse-engineer an aggregate to manufacture event provenance.
2. Old saves remain what they were. Migration can add `LEGACY_UNVERSIONED` provenance, but it must not backfill fake shot rows or relabel synthetic player values as observed.

## 7. Architecture boundary: decision priors, facts, reducers and display

```text
immutable fixture/lineup/tactic snapshot + canonical seed
                         |
                         v
        score/chance-prior boundary (current tactical core;
        future Compartment Engine only after separate approval)
                         |
              strength + tactical matchup + chance priors
                         |
                         v
              Reduced Match Fact Generator
        shots/outcomes/cards/substitutions/appearances
                         |
                         v
                 canonical fact ledger
             +-----------+-----------+
             |                       |
             v                       v
      score/team/player          Animation V3
          reducers             presentation only
             |
             v
      versioned API + provenance
```

The score/chance-prior boundary owns player-strength evaluation, tactic interaction, home/neutral context, extra-time scaling and chance-rate priors. It must not persist `shots`, `passes`, `tackles` or awards. The reduced fact generator owns sampled match actions and their deterministic random streams. Reducers own arithmetic and reconciliation; they do not sample. Animation owns frames and commentary only.

TITAN's pending Phase 0/1 explicitly says it is runtime-inert, has no call from scoring/live/Fast Forward/knockout and is not calibrated (`51806fc78f1c554277070184ce6b0a55bb4ea2a0:docs/compartment-engine/COMPARTMENT_ENGINE_V1_ARCHITECTURE.md:5-19`). Its intended one-engine boundary and immutable decision are compatible with this plan (`51806fc78f1c554277070184ce6b0a55bb4ea2a0:docs/compartment-engine/COMPARTMENT_ENGINE_V1_ARCHITECTURE.md:21-57`, `51806fc78f1c554277070184ce6b0a55bb4ea2a0:docs/compartment-engine/COMPARTMENT_ENGINE_V1_ARCHITECTURE.md:100-114`), but the analytics work must compile and operate against the current tactical core unless/until that commit is approved. Do not copy TITAN coefficients into analytics or add a second scorer.

The current canonical path also has two aptitude leaks to resolve before any “same snapshot, same priors” claim: team-talk scaling reconstructs a two-argument profile and loses aptitude multipliers (`MatchRoundSimulator.java:1394-1396`), and live current-on-pitch recalculation constructs two-argument `StarterValue` objects (`LiveMatchSimulationService.java:1125-1155`). These belong to the decision adapter boundary, not to reducers.

## 8. Decision package

### Option A — provenance-only hardening

Add stable fixture keys to current aggregates, publish source/version badges, fix mislabeled fields and keep every existing generator.

- **Benefits:** smallest migration, minimal CPU/storage, immediately honest UI, low rollout risk.
- **Tradeoff:** score, shots and xG remain score-conditioned; no replayable player shot or GK truth; current player statistics remain attribute estimates.
- **Use when:** the product only needs transparent arcade analytics and no downstream feature will depend on event causality.

### Option B — reduced canonical fact ledger (recommended)

Use MatchPlan as the transaction/idempotency shell; add a compact per-shot/outcome ledger plus canonical cards/substitutions/appearances; derive score, xG, saves and a small player-stat view from it; label all remaining aggregates as estimated.

- **Benefits:** fixes the highest-value causal/reconciliation gaps; supports credible match reports, player shooting/GK stats and auditability; bounded event volume; works for live/instant/AI/Fast Forward/knockout.
- **Tradeoff:** moderate schema/API/save-load/calibration work; compatibility bridge required while current score engines provide totals or priors; default-off rollout must keep old saves readable.
- **Use when:** analytics should drive awards, player comparison or future tactical explanation without implementing a vendor feed.

### Option C — standard possession/action engine now

Persist possessions, passes, carries, duels, fouls, recoveries and shots, then derive all aggregates and advanced metrics.

- **Benefits:** true pass/territory/pressing analytics and a clean route to xA/PPDA/field tilt.
- **Tradeoff:** largest correctness surface, 3–8×-style runtime risk, tens of millions of rows over long careers, major calibration effort and high overlap with future decision-engine work. It delays the smaller truth improvements.
- **Use when:** a committed product requirement names pass/possession/pressing analysis as core gameplay and accepts the performance/storage budget.

### Recommendation

Choose **Option B**, delivered in small default-OFF phases. It gives a coherent and testable football truth without pretending that a full event provider or GPS system exists. Option A is the safe fallback if schema budget is unavailable; Option C should remain an explicit later product decision.

Questions that materially change the recommendation:

1. Must historical saves display newly “credible” metrics, or is `LEGACY_UNVERSIONED` acceptable for old matches? Reconstructing history would be false provenance; a requirement for historical comparability may force parallel legacy/current views.
2. Are player shooting/GK stats and awards intended to affect gameplay decisions next release? If not, Option A may be enough for the next milestone.
3. Is the Compartment Engine expected to be approved and integrated before analytics runtime work? If yes, Phase 1 should consume its reviewed chance-prior contract; if no, build a current-engine adapter and keep that interface replaceable.
4. Is multi-database support required for new gameplay tables now, or is H2 the only supported save runtime? The current repo has Flyway locations only for H2 in production config (`application.yml:8-16`), while security migrations exist for MySQL/PostgreSQL.
5. What is the accepted 100-season storage budget and Fast Forward slowdown ceiling? Those numbers determine whether core events retain every miss/card or only compact shot facts plus aggregates.

## 9. Recommended MVP and phased backlog

Every phase below is independently reviewable, deployable with its flag default OFF, and reversible without deleting old rows. “Probable files” are planning targets, not authorization to edit them.

### Phase 0 — canonical identity, provenance and parity gate

**Usable outcome.** A flagged fixture has one stable identity across plan/result/events/stats/scorers/ratings, explicit engine/source versions, and a machine-checkable reconciliation response. No new football metric is introduced.

- **Scope:** add `fixtureKey`, `engineVersion`, `sourceKind`, `reconciliationStatus` to match-facing aggregate contracts; eliminate discarded canonical live re-score; preserve aptitude dimensions in score adapters; add API v2 envelope while retaining v1.
- **Non-scope:** no shot/pass/physical rows, no recalibration of score distributions, no MatchPlan production enablement.
- **Probable components/files:** `MatchPlan`, `MatchStats`, `Scorer`, `MatchPlayerRating`, `MatchRoundSimulator`, `LiveMatchSimulationService`, `MatchdayCoordinator`, `MatchController`, `StatsController`, repositories and DTOs; frontend fixtures/data-hub/player-analytics badges.
- **Models/migrations/API/frontend:** additive fixture/provenance columns and unique indexes; versioned Flyway migrations for supported dialects; save manifest version bump; `/api/v2/matches/{fixtureKey}` envelope; frontend badge/tooltip with legacy fallback.
- **Flag:** new `match.analytics.provenance-v2.enabled=false`; MatchPlan remains default OFF. No hybrid response inside one fixture.
- **Overlap:** TITAN only through the future engine/config version field; REGENT P2 conflicts in save version/manifest and H2 migration numbering; Animation V3 consumes identity but remains read-only display.
- **Invariants/tests:** same seed/snapshot identity across live/instant/AI/Fast Forward/knockout; retry no-op; two concurrent commits one row; score/result/goal count reconciliation where MatchPlan exists; v1 contract unchanged; save-load and cold-restart preserve source/version; flag-off byte/JSON regression.
- **Calibration:** none for metric values; establish baseline mode-parity and legacy/canonical counts on fixed seasons.
- **Performance/storage:** only additive columns/indexes; measure round latency and index selectivity; target negligible row growth.
- **Observability:** counters by source kind, reconciliation failures, fixture-key collisions, canonical/legacy route counts and commit retry outcomes.
- **Rollback:** turn flag OFF; v1 stays authoritative; additive columns/tables remain ignored and must not be destructively rolled back.

### Phase 1 — reduced shot/outcome ledger and causal core reducers

**Usable outcome.** For flagged new fixtures, persisted shot attempts explain score, team shots/SOT/xG and saves; every aggregate can be rebuilt deterministically from compact facts.

- **Scope:** immutable `MatchFact`/`ShotFact` identity (`fixtureKey`, sequence, period/minute/second, team, shooter, optional assister/GK/blocker, outcome, pre-shot xG, model version, seed stream); explicit own-goal and penalty facts; ET support; score/team/GK reducer; conditional compatibility adapter if current core still supplies a target score.
- **Non-scope:** no full pass/possession/carry/duel feed; no PSxG, shot coordinates finer than stable zones, rebounds beyond what the chosen reduced schema needs; shootout kicks stay separate from match shots/goals.
- **Probable components/files:** new analytics package (`MatchFactGenerator`, `ShotQualityModel`, `MatchFactReducer`, `ReconciliationService`); `MatchPlanService`, `MatchRoundSimulator`, `LiveMatchSession`, `MatchdayCoordinator`, `MatchStatsService`, `MatchEvent` compatibility projection and repositories.
- **Models/migrations/API/frontend:** new compact fact tables and unique `(fixture_key, sequence)`/shot constraints; `MatchStats.fixtureKey`; manifest/save version; v2 fact summary and optionally paged core-event endpoint; fixture screen uses v2 rows and labels legacy estimates.
- **Flag:** `match.analytics.core-ledger.enabled=false`; fixture records the selected path at creation, so flag changes cannot switch an in-progress fixture.
- **Overlap:** consume a narrow `ChancePrior` interface from current tactical core; if TITAN is approved later it implements that interface, never writes facts. REGENT P2 requires manifest/migration rebase. Animation V3 may consume a goal/shot presentation DTO but cannot write outcomes/xG.
- **Invariants/tests:** reducer score equals goal outcomes; SOT ≤ shots, goals ≤ SOT except explicitly versioned own-goal rule; saves equal eligible saved shots; sum shot xG equals team xG; one shooter/team/on-pitch appearance; ET and shootout separation; deterministic same seed; retry/reload equality; concurrent single fact set; live vs instant vs AI vs Fast Forward vs knockout equality for the same snapshot/seed/fidelity; transaction rollback leaves no partial aggregate; save-load rebuild equality.
- **Calibration:** paired-seed distributions for goals, shots, SOT, xG, saves and scorelines; macro 10k PR smoke plus 100k nightly/release gate; publish versioned percentiles/correlations and extreme-tail examples. Current score-conditioned bridge must be labelled as such until a prior-driven generator owns goals.
- **Performance/storage:** target roughly 20–40 compact facts per match, not hundreds/thousands; batch insert; index only fixture/order and essential player lookups; benchmark representative round and 10-season Fast Forward against flag OFF; define hard slowdown and DB-size budgets before rollout.
- **Observability:** reducer mismatch counter, facts/match histogram, generator/reducer latency, xG/outcome bands, fallback/legacy rate, duplicate-key retries and per-mode parity failures.
- **Rollback:** flag OFF routes new fixtures to legacy; already-created ledger fixtures replay by persisted version; never convert them back or delete facts. API v2 can serve both source kinds.

### Phase 2 — real appearances and small player-stat projection

**Usable outcome.** New flagged matches provide trustworthy player minutes, starts/sub appearances, goals, assists, shots, SOT, xG, cards and goalkeeper saves; season leaderboards stop using mislabeled synthetic fields for those metrics.

- **Scope:** project `PlayerMatchStat` from MatchAppearance/core facts; make player-season totals rebuildable; replace `PlayerSeasonStat.shots` ambiguity for new data; keep synthetic pressures/passes/heatmap in a separate estimate namespace; update awards only where the input becomes canonical.
- **Non-scope:** no action-value rating, xA, pressure regains, dribbles, progressive passes or observed heatmaps; no rewrite of legacy history.
- **Probable components/files:** `MatchAppearance`, new `PlayerMatchStat`, `PlayerSeasonStat` successor or versioned columns, `PlayerMatchStatService`, `PlayerAnalyticsService`, `StatsAggregationService`, award services, `StatsController`; player/data-hub/competition frontend components.
- **Models/migrations/API/frontend:** unique `(fixture_key, player_id)` match rows and `(player, competition, season, source_version)` aggregate identity; save manifest/version; v2 player match/season DTOs with source; UI separates “Match facts” from “Attribute estimates”.
- **Flag:** `match.analytics.player-facts.enabled=false`, dependent on core-ledger/provenance flags; failure to meet dependencies prevents fixture creation rather than falling back mid-match.
- **Overlap:** TITAN may supply priors/player snapshots but not player-stat values; REGENT P2 save-manifest version conflict; Animation V3 player positions/frames remain unusable for heatmaps or distance.
- **Invariants/tests:** sum player goals/assists/shots/saves/cards equals team/core facts; minutes follow participant/sub/red-card policy and never exceed match duration; scorer always on pitch; retry/concurrent projection upsert exactly once; rebuild equals incremental aggregates; traded player team attribution defined; live/instant/AI/Fast Forward/knockout parity; save-load and legacy coexistence.
- **Calibration:** position-group distributions for shots/xG/minutes/saves; sample-size thresholds; award before/after comparison on fixed multi-season worlds; no regression that synthetic estimates silently enter factual leaderboards.
- **Performance/storage:** one row per participant who played (roughly 22–32/match), batch writes/rebuilds, player-season indexes; benchmark leaderboard endpoints and multi-season save size.
- **Observability:** projection lag/failure, team-vs-player reconciliation, duplicate seasonal identities, percentage of factual versus legacy player rows, award-input source mix.
- **Rollback:** flag OFF retains v1 synthetic endpoints and old awards; factual rows remain immutable/readable; v2 clients fall back with explicit legacy source, not fake zeros.

### Phase 3 — mode-parity fatigue and estimated physical load

**Usable outcome.** Live, instant, AI and Fast Forward apply the same interval/minute load model and expose clearly estimated minutes, energy and coarse load totals; fitness no longer depends on watching mode.

- **Scope:** reuse canonical appearances and a deterministic interval load reducer; unify stamina/fitness write-back; optional estimated total/high-intensity/sprint-distance bands with model version and uncertainty; position/tactic/tempo/score-state inputs.
- **Non-scope:** no frame-derived distance, no GPS claim, no per-frame tracking persistence, no acceleration catalogue unless a gameplay requirement uses it.
- **Probable components/files:** `LiveMatchSimulationService`, `LiveMatchSession`, `MatchRoundSimulator`, `MatchPlanService`, new `PhysicalLoadEstimate` reducer/model, recovery/training integration and API DTOs.
- **Models/migrations/API/frontend:** either player-match physical columns or a one-row-per-player load table; no interval rows by default; save manifest/version; player/match UI says “estimated load” and shows uncertainty/model version.
- **Flag:** `match.analytics.physical-load.enabled=false`; mode-independent selection is fixed at fixture creation.
- **Overlap:** TITAN may consume starting fitness but cannot derive analytics load; REGENT P2 only save/migration conflict; Animation frames are explicitly excluded as measurements.
- **Invariants/tests:** identical appearance timeline yields identical load in every mode; energy bounded; non-participants unchanged; substituted/red-card players stop accumulating; deterministic retry/concurrency; fitness is written exactly once; save-load resume matches uninterrupted live; knockout ET adds only eligible minutes; Fast Forward produces same fixed-seed result as direct advance.
- **Calibration:** use broad position/load bands and monotonic paired-seed sensitivities (tempo/work rate/minutes), not false precision; gate outliers and 100-season fitness drift.
- **Performance/storage:** interval computation in memory, one aggregate row/player/match; benchmark 100-season Fast Forward; no animation/frame dependency.
- **Observability:** load/minute distributions by position/mode, fitness delta parity, clamping/outlier counts, estimator version and runtime.
- **Rollback:** flag OFF restores existing live-per-minute versus batch-fixed behavior; persisted estimates stay labelled/versioned and do not drive fitness after rollback.

### Phase 4 — optional possession/action expansion (separate product gate)

**Usable outcome.** Only if approved, new fixtures can explain possession and passing from compact sequences and support a small named set such as pass attempts/completions, key passes, field tilt and PPDA.

- **Scope:** possession/sequence identity; passes needed for approved KPIs; linked foul/duel/recovery facts; reducers and paged API; one reviewed fidelity level for ordinary matches.
- **Non-scope:** vendor parity, 3,000 events/match, full coordinates/tracking, VAEP/xT/PSxG, pressure clouds, every pass subtype, historical reconstruction.
- **Probable components/files:** new sequence/action generator/reducers, `MatchStatsService` replacement paths, API pagination, Data Hub/competition views and calibration harness.
- **Models/migrations/API/frontend:** partition-friendly compact action tables, retention policy, manifest/version and paged v2 endpoints; UI only for specifically approved metrics.
- **Flag:** `match.analytics.standard-actions.enabled=false`, dependent on core ledger; no mixed fidelity within a fixture without a persisted fidelity value.
- **Overlap:** strongest dependency on approved upstream chance/tactic priors; highest REGENT manifest/migration collision; Animation remains downstream.
- **Invariants/tests:** possession alternation/ownership, pass attempted = completed + failed, percentages weighted, linked key pass→shot, duel one winner/loser, reducer equality, deterministic retry/concurrency/reconciliation/save-load, exact live/instant/AI/Fast Forward/knockout semantics at the same fidelity.
- **Calibration:** 100k versioned distribution/correlation release gate for possession, pass volume/accuracy, directness and approved defense metrics; tactical paired-seed sensitivity.
- **Performance/storage:** explicit CPU/row/GB budget and retention/index plan before code approval; compare BASIC core ledger against STANDARD actions over 100 seasons.
- **Observability:** facts/sequence, CPU per match, storage growth, endpoint pagination latency, reducer drift and mode/fidelity mix.
- **Rollback:** flag OFF creates core-only fixtures; existing STANDARD fixtures remain replayable/readable by version; no down-conversion.

## 10. What is not worth building now

- **A 100-metric clone of StatsBomb/Opta.** The product lacks the underlying action/tracking feed; adding labels first would compound false precision and schema cost.
- **Full pass persistence for every Fast Forward match.** No confirmed gameplay feature currently needs it, while long-career row growth and save/load cost are material.
- **Frame-derived distance, sprints or heatmaps.** Animation V3 is presentation-only, may fall back, and generates frames from canonical facts; using it as measurement reverses causality (`AnimationV3GoalAdapter.java:22-33`).
- **PSxG, goals prevented, VAEP or xT in the MVP.** They require stable shot placement/action context and calibration that the reduced ledger intentionally does not promise.
- **Backfilling fake events into old saves.** It would create invented history and erase the most important provenance distinction.
- **A second analytics-owned scoring engine.** Current tactical scoring and the pending Compartment work already define the upstream decision boundary; analytics should consume priors/facts and reduce them.
- **Turning MatchPlan and Animation V3 flags on as part of documentation delivery.** Their code and tests reduce risk, but production activation is a separate review/calibration/rollout decision.
- **Using pending TITAN or REGENT commits as dependencies before ATLAS approval.** TITAN is explicitly inert/un-calibrated; REGENT P2 independently changes migration/save versions. Plan interfaces and rebase points, but do not assume merge order.

## 11. Acceptance package for the recommended direction

Before implementation approval, owners should decide the five questions in Section 8 and record:

1. selected option (A, B or C) and supported database dialects;
2. exact MVP metric list and provenance for each metric;
3. fixture fidelity/source immutability rule;
4. Fast Forward slowdown, 100-season storage and save-size budgets;
5. calibration bands and whether they are hard PR, nightly or release gates;
6. migration/save-version ownership relative to pending REGENT work;
7. whether current MatchPlan activation is a prerequisite, a co-delivery or an independent rollout.

The recommended first implementation review should be Phase 0 only. Phase 1 should not start until Phase 0 proves identity/retry/parity and the upstream chance-prior boundary is chosen. No later phase should be bundled merely to make the UI look complete.
