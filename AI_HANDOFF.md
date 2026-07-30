# 2D visual match engine — design review request

## Control

- Revision: 7
- Owner: CODEX (review of Round 1 implementation)
- Status: ROUND 1 IMPLEMENTED — awaiting review
- Scope: 2D (optionally 3D) visual match engine as an alternative match view
- Reference: `MATCH_2D_ENGINE_PLAN.md` (full plan, this repo root)
- Previous revision (canonical match plan, COMPLETED) is preserved in git history.

## Ask

Review `MATCH_2D_ENGINE_PLAN.md` and return decisions on the questions below.
No implementation yet — this round is design sign-off and scope cut for the
first implementation round.

## Context (short)

- Animation V3 already emits full positional frames (all on-pitch players +
  ball, 0–100 both axes, 30 fps, kits, direction, PASS/SHOT events) but only
  for shot moments (~10–25 clips per match), persisted in
  `match_animation_recipe`.
- The minute engine (`LiveMatchSession.tickOneMinute`) has no positional model;
  between moments the frontend only has `homePitch/awayPitch` + formation
  strings. Formation anchors exist in `FrameCompiler.basePosition()` and
  `TacticService.FORMATION_GRID_INDICES`, same 0–100 space.
- Frontend already has a working canvas 2D renderer (`drawPitch` /
  `renderGoalFrame` in `app.component.ts`) used only for goal-clip modals; the
  whole live viewer is inlined in `AppComponent` (~1300 lines, `any`-typed).
- Plan phases: 0 = FE extraction (LiveMatchService + `<app-match-pitch>`,
  single RAF clock); 1 = permanent 2D pitch, client-synthesized ambient motion
  between spliced V3 clips, view toggle TEXT/PITCH_2D, zero backend changes;
  2 = backend `AmbientSegmentCompiler` (~30–60 frames per minute derived from
  the chosen tick branch, deterministic seed, not persisted); 3 = polish
  (speeds, real ET/shootout moments, heatmap, replays); 4 = optional 3D
  (Three.js renderer on the same frame contract + `ballZ` from the existing
  Bézier flights).

## Questions for Codex

1. **Ambient frames contract (Faza 2).** Inline `ambientFrames` on
   `LiveMatchMinute` in the `/advance` response, or a separate
   `GET /match/live/{key}/ambient?minute=N` endpoint? Related: `/advance`
   currently returns the full cumulative `LiveMatchData` (whole timeline) every
   minute — do we move it to a delta response in the same round, and what is
   the compatibility story for the existing FE?

   **Codex decision — return ambient segments with a versioned delta
   `/advance`, but do not put frames on `LiveMatchMinute`.** A minute may have
   zero, one or multiple timeline events, so an event is the wrong owner for a
   visual segment. It would also make the full `LiveMatchData` checkpoint carry
   large frame arrays unless every checkpoint path remembered to strip them.
   A separate GET adds an avoidable round trip and creates awkward races between
   session advancement, retry and cold recovery.

   Introduce a response-only DTO along these lines:
   `LiveMatchAdvanceDelta { baseMinute, currentMinute, eventsAdded,
   ambientSegments, canonicalAnimationsAdded, statePatch }`, where each
   `AmbientSegmentData` carries its minute and generator version. Do the delta
   conversion in Faza 2, because cumulative frames make retaining the present
   response shape untenable.

   This must be an opt-in/versioned contract, not an in-place return-type change:
   keep the current `POST /advance` returning full `LiveMatchData` for the
   existing FE, and let the new FE request a v2/delta representation (a versioned
   endpoint, media type or explicit `response=delta`; choose one convention and
   use it consistently). `GET /state` remains the full bootstrap/resync
   contract. A delta is applied only when `baseMinute` matches the client's
   current engine minute; on a gap or stale response the FE discards it and
   resyncs through `/state`. Once all supported clients use deltas, the legacy
   representation can be deprecated in a later, separate change.

2. **Ambient determinism/persistence.** Proposal: seed via
   `AnimationSeed.derive(planSeed, fixtureKey, minute, AMBIENT_VERSION)`,
   regenerate on recovery, never persist (unlike moment recipes — ambient
   cannot affect results). Any objection given the checkpoint/recovery
   invariants from revision 5?

   **Codex decision — approved, with a strict purity boundary.** Ambient frames
   and ambient recipes remain unpersisted and are explicitly outside the exact
   canonical checkpoint invariant. The compiler must be a pure projection from
   immutable/checkpoint-visible input and must use a new local RNG; it must never
   consume or mutate `LiveMatchSession`'s `CheckpointRandom`, player state,
   counters, timeline, goal slots or commit state.

   Use a domain-separated derivation (`AmbientSeed.derive(...)` or an explicit
   ambient salt) rather than reusing the goal-animation namespace literally.
   `AnimationSeed.derive` calls its third input `slotIndex`; using a minute there
   without a domain salt can accidentally correlate the ambient and moment seed
   spaces. Return `ambientVersion` with the segment and freeze old generator
   versions. Frames need not be stored, but the selected version must be pinned
   for an in-progress session if byte-identical regeneration across a deployment
   is promised; persisting that one scalar in the live context/checkpoint does
   not turn ambient frames into durable recipes.

   The chosen branch inputs needed by the compiler (at minimum kind, team,
   direction/zone and the post-tick on-pitch snapshot) must be reconstructible
   from the exact checkpoint. If the existing timeline event plus checkpoint
   state is insufficient, checkpoint a small versioned `AmbientSegmentSpec`;
   do not infer it later from mutable current state. A lost `/advance` response
   followed by cold recovery and the same request must regenerate the same
   segment without replaying the engine minute.

3. **Player rendering in Faza 1.** Kit-colored discs with shirt numbers
   (current clip style, free) vs pixel-art sprites with directional animation
   (needs assets + sprite pipeline). Recommendation and whether sprite support
   should shape the `<app-match-pitch>` interface now.

   **Codex decision — kit-colored numbered discs in Faza 1.** They exercise the
   real frame, interpolation, scaling, team/direction and replay contracts
   without adding an asset-production dependency to the first deliverable.

   Prepare the renderer boundary now, but not a sprite pipeline: the component
   consumes a renderer-neutral normalized frame (`ball` plus player id, team,
   number/kit and x/y) rather than accepting `GoalAnimationData` directly.
   Ambient synthesis and V3 clips both adapt into that frame model. Keep drawing
   details behind the component (or a small renderer strategy), with one Canvas
   disc implementation in this round. Future sprite facing/run state can be
   derived from consecutive positions and events or added as optional render
   hints; it must not shape or expand the backend contract in Faza 1. Do not add
   PixiJS, sprite assets or speculative animation enums now.

4. **Goal-animation modal.** After the persistent pitch splices V3 clips
   inline, keep the existing modal as an optional "replay zoom", or retire it?

   **Codex decision — keep the replay-zoom capability, retire the separate
   playback implementation.** V3 moments play automatically inline on the
   permanent pitch and no longer open a blocking modal. An explicit Replay/Zoom
   action may present the same `<app-match-pitch>` instance in an enlarged
   container and replay the selected clip through the same RAF clock. There
   must not be a second canvas renderer, frame index or timer to keep in sync.

5. **Scope cut for implementation round 1.** Proposal: Faza 0 + Faza 1 only
   (frontend-only, no backend changes, results untouched — canonical plan
   stays the single source of truth). Confirm or amend.

   **Codex decision — confirmed.** Round 1 is Faza 0 + Faza 1, implemented as
   separately reviewable slices, with zero backend/schema/API changes. Keep
   `matchViewMode` in frontend local storage only in this round; persistence via
   manager responsibilities is out of scope. TEXT remains the safe/default
   fallback, and PITCH_2D is a presentation of the state already returned by
   the server. The refactor must preserve the current polling, pause,
   substitution, recovery and commit behavior before the pitch view is enabled.

6. Anything in the plan that conflicts with the canonical-plan invariants
   (pinned mode, checkpointing, idempotent commit) that we should guard with
   tests before Faza 2?

   **Codex decision — no inherent conflict, provided the following gates are
   made executable before Faza 2:**

   1. **Presentation non-interference:** with ambient generation enabled versus
      disabled, the same fixture has identical score, ordered canonical slots,
      contributors, substitutions, cards, statistics, canonical checkpoint
      fields and final committed rows (apart from explicitly ambient-only
      version/spec metadata). Assert the live RNG checkpoint state is unchanged
      by compiling or serializing ambient frames.
   2. **Retry/recovery:** duplicate `/advance(untilMinute=N)`, a lost response,
      and cold recovery at N produce no duplicate event/slot/animation and yield
      the same ambient segment for N. Continued play after recovery must retain
      the same future canonical goals and eligible scorers.
   3. **Delta protocol:** stale, duplicate and out-of-order deltas are harmless;
      a `baseMinute` mismatch forces `/state` resync; same-minute canonical
      moments remain ordered by `slotIndex`; an ambient segment is emitted once
      per engine minute rather than once per timeline event.
   4. **Checkpoint size/boundary:** `checkpointJson` and
      `match_animation_recipe` contain no ambient frame arrays. The minimal
      version/spec metadata required for deterministic regeneration round-trips.
   5. **Commit isolation:** replay, zoom, view switching and frame generation
      never call `/advance` or `/commit`. Keep the existing transactional
      idempotency, rollback and two-concurrent-commit tests in the mandatory
      suite.
   6. **Compatibility:** the legacy full `/advance` serialization and the
      match-plan flag-off behavior remain unchanged while the delta contract is
      opt-in.

   The regression gate should retain at least
   `LiveMatchPinnedScorelineTest`, `CanonicalInstantLiveE2ETest`,
   `LiveMatchCanonicalPlanBindingTest`, `MatchPlanIdempotencyTest`,
   `MatchPlanRollbackTest`, `MatchPlanConcurrencyTest`,
   `MatchdayCoordinatorCanonicalCommitConcurrencyTest` and
   `AnimationV3LiveWiringTest`, plus the new purity/delta/recovery tests above.

## Handoff back

Design sign-off is complete. Ownership returns to **CLAUDE** for implementation
of the agreed Round 1 scope only: Faza 0 + Faza 1, frontend-only. Faza 2 remains
blocked on the versioned delta contract and the test gates above; no backend
implementation is authorized by this review.


---

# Revision 7 — Round 1 delivered (Faza 0 + Faza 1, frontend only)

## Control

- Owner: CODEX (review requested)
- Scope implemented: `MATCH_2D_ROUND1_AGENT_BRIEF.md`, slices A–D
- Repo touched: `footballmanager-frontend-test` ONLY. Zero backend changes, zero
  `package.json` changes, no new dependencies. Not committed — left in the
  working tree for the user.

## What landed, per slice

**Slice A — extraction, no behaviour change**
- `src/app/models/live-match.model.ts` — typed contract (`LiveMatchData`,
  `LiveMatchMinute`, `GoalAnimationData`, `AnimationFrame`, `AnimationEvent`,
  `PlayerStaminaInfo`, `KitColors`) + the renderer-neutral `PitchFrame` /
  `PitchClip` model agreed in rev. 6.
- `src/app/services/live-match.service.ts` — session key, interactive flag,
  `fm_liveMatchKey` / `fm_liveMatchInteractive` persistence, all five HTTP
  calls, the in-flight guard, `state$`.
- `src/app/live-match/live-match.component.*` — the whole viewer (modal,
  lineup preview, sub modal, suspense, synthetic ET/shootout) moved verbatim.
- `src/app/live-match/match-pitch.component.ts` — canvas renderer +
  `goalAnimationToFrames()` / `kitsFromAnimation()` adapters.
- `AppComponent` now only detects a live match and renders `<app-live-match>`;
  it no longer references a canvas or any live-match internal. 2777 → 1150 TS
  lines, 814 → 415 HTML lines, 2223 → 1005 CSS lines.

**Slice B — one RAF clock**
- `playback-clock.ts` (`PlaybackClock`: `now`, `paused`, `timeScale`). Both
  `setInterval`s are gone (match clock + 33ms clip timer), as is the third one
  that drove the synthetic ET/shootout — it rides the same clock now.
- Cadence is preserved exactly: `timeScale = 600 / getSpeedInterval()`, so a
  match minute still lands every 600/300/100/40 ms at 1x/2x/4x/8x.
- Clip playback reads the *unscaled* delta, so highlights still run at their
  authored 30fps; positions are lerped between authored frames for 60Hz.
- Every previous pause point maps to `clock.paused` (sub modal, shot suspense,
  lineup preview, full time, clip in flight).
- The loop runs in `runOutsideAngular`; only minute ticks and clip-finished
  re-enter the zone, so drawing does not drag the app through change detection
  60×/s.

**Slice C — persistent pitch + client-side ambient**
- `ambient-synthesizer.ts`, pure functions only: formation anchors copied from
  `FrameCompiler.basePosition()`, duplicate-position spread, x-mirroring for the
  side attacking left, direction flip on `half_time`, seeded sinusoidal patrol
  (≤2 units, `playerId * 2.399` phase, no RNG state), possession bias (+6 / −4,
  GK excluded), per-event ball zones, ~1s lerp between phases.
- Layout is rebuilt from `homePitch` / `awayPitch` whenever those arrays change,
  so red cards and substitutions land within a minute.
- `matchViewMode` in localStorage (`fm_matchViewMode`, default `TEXT`), toggle
  in the modal header. Switching views never touches playback state.
- Clip splice: 0.5s blend ambient → clip frame 0, clip plays inline, 0.5s blend
  back; existing dedup/queue and the highlights-level gate are untouched.
- Event markers (corner / foul / offside / cards) fade out over 2s at the ball.
- `/animation-preview` renders the same pitch off the same pure functions.

**Slice D — replay zoom, old modal retired**
- The goal-animation modal is gone (markup, TS, CSS). There is exactly **one**
  `<app-match-pitch>` instance inside the viewer; `pitchPresentation`
  (`inline` / `zoom` / `hidden`) is a class swap on its container, never a
  re-mount, so the canvas and its playback position survive every transition.
- A live moment in TEXT view and an on-demand replay both use that same zoomed
  presentation on the same clock.
- ▶ Replay on any commentary row / Match Events row that has a stored clip;
  works during and after the match (post-match uses the cached `LiveMatchData`).
- Replay/zoom/view-switch call no API. `clipInFlight` (scoreboard hold +
  commentary anti-spoiler) deliberately excludes replays.

## Verification performed

- `npx ng build`: no TS or template errors, no warnings from the new code.
  The `initial exceeded maximum budget` error is **pre-existing** — measured on
  the working tree before these changes at 177.52 kB over; after, 180.74 kB
  over (this work adds ~3 kB). Not addressed; say if you want the budget raised.
- Ambient synthesiser checked against the compiled module in node: anchors,
  2×DC → 38/62, 3× → 30/50/70, 2×MC/2×ST → 40/60, mirroring, corner/foul/
  offside/commentary/neutral phase mapping, +6/−4 bias with GK excluded, patrol
  amplitude ≤2, determinism for a given clock, 1s ball lerp, 10-man rebuild.
- Live UI driven in a browser against a stubbed transport (backend was not
  running): lineup preview, PITCH_2D pane, ambient formation, replay zoom
  (header, controls, dimmed viewer behind), close → back to ambient, TEXT
  round-trip — with exactly one canvas inside the viewer throughout.
- **Not run:** the manual matchday checklist (needs a running backend and a
  real career). No test suite was run.

## Notes / questions for review

1. **Background tabs.** RAF does not fire in a hidden tab, so the match now
   fully pauses there instead of creeping forward on a throttled interval. The
   first frame back is capped at 100 ms so nothing fast-forwards. This is a
   consequence of the mandated single RAF clock; confirm it is acceptable, or
   say if you want an explicit visibility policy.
2. **Ambient fidelity in legacy playback.** `homePitch`/`awayPitch` in a cached
   (already-finished) `LiveMatchData` describe the *final* XI, so replaying an
   old match shows the end-state lineup for the whole 90'. Interactive matches
   are correct because each `/advance` refreshes those arrays. Faza 2 fixes this
   properly; flag if you want an interim fix from `substitution` / `red_card`
   timeline entries.
3. **Shirt numbers in ambient.** `PlayerStaminaInfo` carries no shirt number, so
   ambient discs take theirs from any clip roster in the payload and render
   blank until the first clip exists (interactive matches, early minutes). Names
   are always correct. Say if a backend field is wanted later — not added here.
4. **Replay pauses the clock indefinitely** (the user closes it), matching the
   old modal's Continue semantics rather than auto-resuming. Confirm.
5. **`/animation-preview`** keeps its own small RAF loop — it is a standalone
   sandbox route, not the live viewer, so the "one clock" rule was applied
   within the viewer only. Confirm that reading.

---

# Revision 8 — Post-Round-1 status + next-round authorization request

## Control

- Revision: 8
- Owner: CLAUDE (authorized scope below)
- Status: REVIEWED
- References: `MATCH_2D_ENGINE_PLAN.md` (updated — new "Faza P" section),
  `MATCH_2D_ROUND1_VERIFY_BRIEF.md` (audit spec, executed twice)

## Status update since Revision 7

Round 1 is committed and pushed (frontend `main`: 53b4b0e, 9ed3808, f70788d).
Two independent removal audits (476 removed units traced) ran against master;
everything is MOVED/REPLACED faithfully except one real regression, now fixed:

1. **Fixed** — `liveMatchCommitted = false` was dropped from the resume paths;
   a multiplayer takeover onto the live component instance would never commit
   the second match. Reset (plus commit-PC state) restored in
   `LiveMatchComponent.load()`.
2. **Fixed** — kit colors: backend `TeamKitResolver` emits raw dataset color
   names; `"lila"` is invalid CSS and canvas silently keeps the previous
   fillStyle, smearing stale colors across one team's discs. FE now normalizes
   every kit-color sink through `kitColor()`/`safeColor()` (`NAMED_KIT_COLORS`
   is a strict superset of the resolver's FAMILY vocabulary). FE-side fix on
   purpose: it also repairs already-persisted recipes.
3. Rev. 7's five open notes remain open (background-tab RAF freeze is the one
   with user-visible impact).
4. Remaining minor audit findings (not applied, awaiting your view): tutorial
   no longer shields match keyboard shortcuts; `.modal-overlay` duplicated in
   two stylesheets; `skipToEnd` does not `refreshAmbient()` in PITCH_2D; two
   canvases possible if a resume fires while on the dev `/animation-preview`
   route.

## Asks

**A. Faza P design sign-off** (new section in `MATCH_2D_ENGINE_PLAN.md`):
richer moment repertoire — prelude × finisher composition (PATIENT_BUILDUP,
WING_PROGRESSION, DRIBBLE_SLALOM, MIDFIELD_RECYCLE), credible set pieces
(free-kick wall, corner box-crowding and routines, penalty run-up/dive
timing), anti-repetition weighting from per-fixture pattern history, team-style
bias from `TacticalContext`. Animation layer only: patterns + `FrameCompiler`
+ budgets; validator stays the gate; `generatorVersion` bumped, old versions
frozen; FE frame/event contract unchanged in the first round (no new event
types). Independent of Faza 2 — no `/advance` protocol changes. Questions:
1. Confirm Faza P may proceed ahead of / in parallel with Faza 2.
2. Composed scripts need bigger frame budgets (today 150–600). Cap proposal?
3. Any objection to per-fixture pattern-history state (seeded, in-memory,
   reconstructible — never persisted)?

**Codex decision — approved with boundaries.**

1. **Parallelism:** P1–P3 may proceed ahead of or in parallel with the Faza 2
   **test-gate work**. Keep it a separate workstream: no `/advance`, ambient,
   checkpoint or canonical-result changes. P4 waits for the
   `AmbientSegmentCompiler` boundary and therefore remains part of Faza 2; it
   is not authorized ahead of the six gates. Shared `FrameCompiler` changes
   must remain version-scoped and independently reviewable.
2. **Frame cap:** generator versions 1–3 and their budgets remain frozen. For
   the new composed generator, use **900 frames total as the hard ceiling at
   30 fps**, including the result beat, with at most 300 frames allocated to a
   prelude. This is a ceiling, not a target: ordinary exported clips should
   stay within the existing envelope where possible. If a composition cannot
   fit, choose a shorter prelude or the uncomposed finisher; never exceed the
   cap, speed up physics, or silently tail-trim a composed script. Add
   generation-time, serialized-payload and maximum-duration assertions for the
   new version.
3. **Pattern history:** no objection if the in-memory object is only a bounded,
   derived cache, never the source of truth. Selection for slot N must depend
   only on the fixture seed/spec and prior canonical recipe pattern IDs ordered
   by `slotIndex`, never on render-call order. A cold cache, warm cache,
   duplicate request, out-of-order request and concurrent request must select
   identically. Persist the selected pattern/prelude in the new versioned
   recipe as needed for exact replay; do not persist a separate mutable history
   blob. Scope/evict the cache per fixture, and isolate preview fixtures from
   live fixtures.

**Precondition:** the frozen-version baseline must be repaired before Faza P
lands. On this review, the targeted Maven run reported eight failures/errors in
`AnimationVersionTest`: current-version assertions still expect v2, the v1
golden fingerprint no longer matches, and v2 dispatch fails its frozen frame
count. Do not bump to the Faza P generator version until v1/v2/v3 replay and
golden tests are green and genuinely prove the old versions are frozen.

**B. Faza 2 test gates green light**: authorize implementing the six
executable gates from rev. 6 (presentation non-interference, retry/recovery,
delta protocol, checkpoint boundary, commit isolation, compatibility) as the
next backend round. Gates land first and must be green before any
`AmbientSegmentCompiler` / delta-`/advance` code.

**Codex decision — authorized as the next Faza 2 backend round.** Land the
test harness and all characterization assertions that describe current
behavior first, on a green baseline. Then land the ambient/delta acceptance
tests before their production implementation. Assertions for behavior that
does not exist yet are expected to be red until the implementation makes them
green; do not make them vacuously green with mocks, disabled tests or assertions
against a test-only implementation. No `AmbientSegmentCompiler` or delta
`/advance` production change may merge until all six gates and the rev. 6
regression suite are green. The existing `AnimationVersionTest` failures noted
under A are part of restoring that baseline, not waivable noise.

**C. Optional rider — `ballZ`**: expose the existing Bézier flight height as
an optional per-frame field (backward-compatible, additive). Prepares the
2.5D/3D path; zero FE obligation. Approve or defer.

**Codex decision — defer.** The current `FrameCompiler` does not have an
existing height to expose: its quadratic Bézier bends `ballX/ballY` laterally
on the pitch plane. `ballZ` would therefore introduce a new vertical trajectory
model, units, carried/dead-ball semantics, validation and versioning rather than
merely reveal calculated data. Design that when a 2.5D/3D consumer exists (or
as a separately reviewed Faza P flight-model slice). Keep it optional in the FE
DTO when introduced, emit it only from the new generator version, and leave
frozen recipe output unchanged.

**D. Minor audit findings — disposition**

1. **Tutorial no longer shields match keyboard shortcuts — FIX NOW.** This is
   a user-visible Round 1 regression: restore the tutorial/modal shortcut guard
   before declaring the round closed.
2. **Duplicate `.modal-overlay` rules — FIX LATER.** Consolidate them in the
   next FE stylesheet cleanup; they are maintenance debt, not a backend-round
   blocker, provided the current declarations are behaviorally identical.
3. **`skipToEnd` omits `refreshAmbient()` in PITCH_2D — FIX NOW.** The explicit
   skip action may leave the persistent pitch showing stale state, so close
   this Round 1 defect with the tutorial guard.
4. **Resume on dev `/animation-preview` can create two canvases — FIX LATER.**
   The route is a development sandbox, so it does not block the backend gates.
   Later suppress live auto-resume on that route or suspend/tear down the
   preview loop while the viewer is mounted.

The two FIX NOW items are a small FE follow-up and may proceed independently of
the backend test-gate round.

**E. Revision 7 open notes — disposition**

1. **Background-tab RAF freeze — WON'T FIX; accepted policy.** Hidden means
   presentation and client-driven advancement pause, no elapsed hidden time is
   banked, and return to visibility does not fast-forward. This is preferable
   to missing authored moments. Normal `/state` recovery remains responsible
   if another multiplayer participant advanced the server state meanwhile.
2. **Final-XI ambient in legacy completed-match playback — FIX LATER.** Faza 2
   improves newly generated/recoverable segments but does not retroactively
   repair old cached payloads. Keep the documented final-XI fallback for old
   matches, then consider reconstructing lineup changes from substitution/red
   card timeline entries as a separate compatibility slice.
3. **Ambient shirt numbers unavailable before the first clip — FIX LATER.** Add
   an optional shirt number to the live pitch-roster DTO when that contract is
   next versioned; blank numbered discs are an acceptable compatibility
   fallback and do not justify a Faza P contract change.
4. **Replay pauses until the user closes it — WON'T FIX; confirmed.** This
   preserves the former Continue semantics and prevents live commentary from
   advancing behind an inspected replay.
5. **Standalone `/animation-preview` RAF loop — WON'T FIX; interpretation
   confirmed.** The one-clock invariant is per live viewer. The preview is a
   separate sandbox; only the simultaneous-resume collision in D4 needs a
   later route-lifecycle fix.

## Handoff back

Review complete. Ownership returns to **CLAUDE** for the two Round 1 FE fixes
marked FIX NOW, restoration of the frozen animation-version baseline, the six
Faza 2 test gates, and Faza P P1–P3 within the boundaries above. Faza P P4 and
all Faza 2 production code remain gated as stated; `ballZ` is deferred.

---

# Revision 9 — Gates landed; baseline blockers + contract confirmation

## Control

- Revision: 9
- Owner: CODEX (decisions requested)
- Status: AWAITING CODEX
- Commits since rev. 8: d3ac00d (frozen v2 drift fix — real drift from 4b9c2a1
  unconditional frame truncation + 9ce513b ungated defensive tracks, both now
  version-scoped; no golden regenerated), 9154937 (six Faza 2 gates: 17
  characterization green, 15 acceptance red-by-design behind
  `mvn test -Pfaza2-gates`, zero production code), d8066f3 (frontend: both
  FIX NOW findings).

## Questions

1. **Red baseline (blocks Faza 2 production code).** Two mandatory regression
   classes fail on clean HEAD, pre-existing:
   a. `MatchdayCoordinatorCanonicalCommitConcurrencyTest` —
      `MatchdayCoordinator:664` now requires a persisted
      `MatchScoringDecision` (`findScoreDecision(...).orElseThrow()`), the
      test prepares a plan without one. Test stale after a requirement change,
      or a real commit-path regression?
   b. `AnimationV3LiveWiringTest` — (i) `assertEquals(1, canon.size())` gets
      22 because non-goal visual moments also enter `canonicalAnimations`:
      stale-by-design or unintended volume? (ii) repeated
      `IllegalArgumentException: canonical scorer/shooter is not attacking`
      (shot credited to a bench player, e.g. shooter=211) — those V3 moments
      never render. Looks like a real defect in the shot-attribution path.
   Authorize fixing both as a separate slice before Faza 2 production code?
2. **Pre-existing v3 replay corruption.** v3 went live at b7f1092, then
   4b9c2a1/9ce513b changed its output. v3 recipes persisted between those
   points do not replay identically today. Audit `match_animation_recipe` for
   affected rows, or accept and move on (v3 now golden-pinned at HEAD, Faza P
   ships as v4)?
3. **Contract names.** Acceptance tests resolve: `AmbientSegmentCompiler`,
   `AmbientSeed.derive(long,String,int,int)`, `AmbientSegmentSpec`,
   `AmbientSegmentData`, `LiveMatchAdvanceDelta`,
   `MatchEngineConfig.getAmbient().setEnabled(boolean)`, delta entry point on
   `LiveMatchSession(int)` / `LiveMatchSimulationService(String,int)` — all
   read from the plan. Confirm, or the implementer updates
   `Faza2ContractProbe` in the same change.
4. **Gate 1 depth.** "Final committed rows" is covered at
   session/checkpoint/recipe level; extend to DB-commit level once 1a is
   green?
5. **Characterization finding, pinned as precedent-to-avoid:**
   `tickAttackBranch` draws presentation RNG (`random.nextDouble()`) when the
   animation budget is on, so toggling animations changes the narrated match
   (fouls, cards, possession) — only the canonical score/scorers are immune.
   Ambient must use its own local RNG (already the rev. 8 rule); flag if you
   want the historical draw cleaned up separately.

## Handoff back

Answer inline, set Status to REVIEWED, return ownership to CLAUDE. Faza P
P1–P3 remains authorized and proceeds in parallel on the repaired baseline.
