# Round 1 implementation brief — 2D match view (for implementing agent)

You are implementing Round 1 (Faza 0 + Faza 1) of the 2D visual match engine.
Design is signed off — you make NO design decisions. Where this brief and the
code disagree on line numbers, trust the code; where they disagree on behavior,
stop and report instead of improvising.

## Repos and scope

- **You touch ONLY the frontend**: `/Users/ciprian.amza/Downloads/footballmanager-frontend-test/`
  (Angular 15, single NgModule, no lazy loading, no standalone components).
- **You never touch the backend** (`/Users/ciprian.amza/IdeaProjects/footballmanager-backend-`).
  No new endpoints, no DTO changes, no schema changes. The backend is running
  contract truth; read it only for reference.
- Reference docs (backend repo root, read-only): `MATCH_2D_ENGINE_PLAN.md`
  (plan + decisions), `AI_HANDOFF.md` rev. 6 (Codex sign-off).
- **No new npm dependencies.** Canvas 2D only. No PixiJS, no sprite assets,
  no speculative animation enums.
- **Do not run the test suites unprompted. Do not commit to master** — work in
  a branch if the repo allows; otherwise leave changes unstaged. The user
  commits. Definition of done per slice = `npx ng build` clean + manual flow
  checklist below.

## Current state (verified 2026-07-30)

Everything live-match lives inline in the root component:

- `src/app/app.component.ts` — 2777 lines. Live match logic ≈ lines 90–2700.
- `src/app/app.component.html` — 814 lines. Live modal :344–576, lineup
  preview :578–669, goal-anim modal :671–689, sub modal :691–751.
- `src/app/app.component.css` — 2223 lines (live modal + goal anim styles).
- Backend base URL: `export const urlApp = "http://localhost:8086"` at
  `app.component.ts:10`; every service imports it from `app.component`.
- `AuthInterceptor` (`src/app/services/auth.interceptor.ts`) adds
  `withCredentials` + XSRF header — don't bypass HttpClient.

Key existing members in `app.component.ts` (approx. lines):

| Concern | Members |
|---|---|
| Entry | `advanceGame()` :545, `fetchLiveMatch()` :1066, `maybeResumeLiveMatch()` :437 (localStorage keys `fm_liveMatchKey`, `fm_liveMatchInteractive`), multiplayer resume :20 |
| Clock | `startLiveMatchTimer()` :1110 — `setInterval` at `getSpeedInterval()` :1508 (1x=600ms, 2x=300, 4x=100, 8x=40); `setLiveMatchSpeed()` :1517; `stopLiveMatchTimer()` |
| Modes | `tickPlayback()` :1129 (legacy full timeline), `tickInteractive()` :1203 (`POST {urlApp}/match/live/{key}/advance?untilMinute=N`, guard `liveAdvanceInFlight`, response replaces `liveMatchData` wholesale) |
| Display | `liveVisibleEvents` :1801, suspense `isSuspenseShot()`/`applyShotSuspense()` :1161/:1179, scoreboard :1879, `liveMatchPanelView` :134, stamina snapshot :1957, `keyMatchEvents` :1924 |
| End | `commitInteractiveLiveMatch()` :1267 (`POST /commit`), `skipToEnd()` :1528 |
| Subs | state :125–130, `canMakeSubstitution` :1614, `userIsHome` :1629, `userPitch/userBench` :1634/:1640, `applySubstitution()` :1693 (`POST /substitute`, merges returned state, `openSubModal` pauses timer) |
| Synthetic ET/pens | :98–123, `tickSynthetic`, `beginSyntheticShootout` (cosmetic, seeded FE fabrication — keep as-is in Round 1) |
| Goal clips | canvas `#goalCanvas` (`app.component.html:680`), `startGoalAnimationPlayback()` :2261 (setInterval 33ms), `renderGoalFrame()` :2293, label-collision pre-pass :2325, `numberColorFor()` :2541, confetti :2586/:2621, `drawPitch()` :2636, `animationsAtMinute()` :2011, `playAnimationsAtMinute()` :2031, `shouldPlayAnimation()` :2253 (gates on `localStorage['fm_matchHighlightsLevel']`), canvas init hack via `AfterViewChecked` + `goalAnimationCanvasReady` :1993 |
| Lineup preview | `buildLineupFromMatch()` :2200, `lineupRows()` :2100, `lineupColumn()` :2162 (CSS grid, not canvas) |

Backend contract (do not change; consume as-is):

- `GET /match/live/{key}` — cached final `LiveMatchData`.
- `GET /match/live/{key}/state` — session snapshot (bootstrap/resync).
- `POST /match/live/{key}/advance?untilMinute=N` — full cumulative
  `LiveMatchData` each call.
- `POST /match/live/{key}/substitute` — body `{playerOutId, playerInId, atMinute}`.
- `POST /match/live/{key}/commit`.
- `GET /match/animation/livePreview?teamId1&teamId2` — full `LiveMatchData`
  for an arbitrary pairing; used by `src/app/animation-preview/` (route
  `/animation-preview`) — **your prototyping sandbox; keep it working**.
- `LiveMatchData` fields used: `timeline[]` (minute, homeScore, awayScore,
  eventType, commentary, playerId/Name, teamId/Name), `canonicalAnimations[]`
  (ordered by minute+slotIndex), legacy `goalAnimations{minute→…}`,
  `homeFormation`/`awayFormation` (e.g. `"4231"`), `currentMinute`, `finished`,
  `awaitingCommit`, `homeSubsRemaining/awaySubsRemaining`,
  `homePitch/awayPitch/homeBench/awayBench` (playerId, name, position,
  stamina 0–100, minutesPlayed, onPitch, yellowCardMinute, redCardMinute),
  `staminaSnapshots`, stats block.
- `GoalAnimationData`: `players[]` (playerId, name, shirtNumber, teamId,
  position; attackers then defenders), `frames[]` = `{ballX, ballY,
  ballCarrierId, positions: number[][]}` — **coordinates 0–100 both axes**
  (x = goal line→goal line, y = sideline→sideline), `events[]` (frame, type
  PASS|SHOT|GOAL|SAVE|MISS|BLOCKED, from/toPlayerId), `totalFrames`,
  `homeAttacksRight`, `scoringTeamKit`/`defendingTeamKit`, `outcome`,
  `animationType`, scorer/assister fields.

Formation anchors (copy into FE as a constant table; source of truth is
backend `FrameCompiler.basePosition()`, same 0–100 space, for a team attacking
right → mirror x as `100 - x` for the team attacking left):

```
GK(4,50)  DL(25,14) DC(25,50) DR(25,86) WBL(30,10) WBR(30,90) DM(35,50)
ML(48,14) MC(48,50) MR(48,86) AML(62,20) AMC(62,50) AMR(62,80) ST(72,50)
```

When a formation has multiple players of the same position code (e.g. two DC),
spread them evenly on the y axis around the anchor (e.g. 2×DC → y 38/62,
3×DC → 30/50/70, 2×MC → 40/60, 3×MC → 30/50/70, 2×ST → 40/60). Positions come
from `homePitch/awayPitch[].position`.

## Binding design decisions (Codex sign-off — do not revisit)

1. Player rendering = kit-colored discs with shirt numbers (current clip
   style). Renderer interface is renderer-neutral; no sprite pipeline.
2. `<app-match-pitch>` consumes a **normalized frame model**, never
   `GoalAnimationData` directly:
   ```ts
   interface PitchFrame {
     ball: { x: number; y: number; carrierPlayerId: number | null };
     players: Array<{ playerId: number; teamId: number; shirtNumber: number;
                      x: number; y: number; isGoalkeeper: boolean }>;
   }
   ```
   plus kit colors passed as component inputs. Ambient synthesis and V3 clips
   both adapt into this model.
3. One playback clock: a single RAF loop with `liveMatchSpeed` as time-scale.
   No second canvas, no second frame index, no second timer — the replay-zoom
   presents the SAME `<app-match-pitch>` instance in an enlarged container on
   the same clock.
4. Replay, zoom, view switching and frame generation NEVER call `/advance` or
   `/commit`.
5. `matchViewMode: 'TEXT' | 'PITCH_2D'` in localStorage only (suggested key
   `fm_matchViewMode`); default TEXT. TEXT is the safe fallback; PITCH_2D is a
   pure presentation of state already returned by the server.
6. Zero backend/schema/API changes. The old goal-animation modal's playback
   implementation is retired in Slice D; its capability survives as replay-zoom.

## Slices — implement in order, each independently reviewable

### Slice A — extraction, ZERO behavior change

Goal: live match moves out of `AppComponent` with pixel-identical behavior.

1. Create `src/app/models/live-match.model.ts`: interfaces `LiveMatchData`,
   `LiveMatchMinute`, `GoalAnimationData`, `AnimationFrame`, `AnimationEvent`,
   `PlayerStaminaInfo`, `KitColors` — typed from the contract above (add
   fields you find in actual responses; keep names identical to JSON).
2. Create `src/app/services/live-match.service.ts` (`LiveMatchService`):
   owns session key, interactive flag, localStorage persistence
   (`fm_liveMatchKey`, `fm_liveMatchInteractive`), HTTP calls (`fetch`,
   `state`, `advance(untilMinute)`, `substitute`, `commit`), in-flight guard,
   exposes `state$: BehaviorSubject<LiveMatchData | null>`. Move logic from
   `app.component.ts` — cut/paste with minimal edits, do not rewrite.
3. Create `src/app/live-match/live-match.component.ts|html|css`
   (`<app-live-match>`): move the live modal (html :344–576), lineup preview
   (:578–669), goal-anim modal (:671–689), sub modal (:691–751) and all the
   TS members from the table above, plus their CSS. `AppComponent` keeps only:
   detecting a live match after `advanceGame()` / resume / multiplayer, and
   rendering `<app-live-match *ngIf="...">` with the key. Declare the new
   components in `app.module.ts`.
4. Create `src/app/live-match/match-pitch.component.ts` (`<app-match-pitch>`):
   for now it owns the goal-clip canvas only — move `drawPitch`,
   `renderGoalFrame`, `numberColorFor`, label-collision pre-pass, ball trail,
   confetti, and the `AfterViewChecked` canvas-ready hack into it. Inputs: the
   clip to play (already adapted to `PitchFrame[]` + events + kits by an
   adapter function `goalAnimationToFrames(data: GoalAnimationData)` you write
   in the component's file or a sibling util). Playback timer stays as-is
   (33ms setInterval) in this slice.
5. `animation-preview` component: point it at the same new pieces if trivial;
   at minimum it must still compile and work.

Done when: `npx ng build` clean; manual flow unchanged — start a live match,
speeds 1x–8x, suspense hold on shots, goal clip plays with confetti, make a
substitution (timer pauses/resumes, counter decrements), refresh mid-match
resumes from localStorage, skip to end, commit, knockout text + press
conference id still appear. `AppComponent` no longer references canvas or
live-match internals.

### Slice B — single RAF clock

Goal: one `requestAnimationFrame` loop drives both the match clock and clip
playback.

1. In `<app-live-match>`: a `PlaybackClock` (plain class) with
   `now`, `paused`, `timeScale`; RAF loop accumulates scaled dt.
   - Match-minute advancement: fire when accumulated time crosses the current
     per-minute interval (600/300/100/40 ms ↔ speed 1x/2x/4x/8x) — same
     cadence as today, driving `tickPlayback()`/`tickInteractive()` unchanged.
   - Clip playback in `<app-match-pitch>`: frame index = clip-elapsed × 30fps
     (interpolate between frames for smoothness at 60Hz — linear lerp on all
     positions and ball).
2. Map every existing pause point onto `clock.paused`: sub modal open,
   suspense hold, lineup preview, `stopLiveMatchTimer()` call sites, tab
   hidden (keep current behavior if none exists — do not add new behavior).
3. Remove both old `setInterval`s. `setLiveMatchSpeed()` now sets
   `timeScale` only.

Done when: build clean; same manual checklist as Slice A (behavior identical,
including pause semantics during subs and suspense).

### Slice C — persistent 2D pitch + client-side ambient

Goal: the FM-style always-on pitch, toggleable.

1. Toggle `matchViewMode` (`fm_matchViewMode`, default `'TEXT'`): TEXT renders
   exactly today's layout; PITCH_2D replaces the commentary center pane with a
   persistent `<app-match-pitch>` (commentary collapses to a side column or
   bottom strip — keep it visible, just smaller). Toggle button in the modal
   header. Switching views mid-match must not touch playback state.
2. Ambient synthesis (new `ambient-synthesizer.ts`, pure functions →
   `PitchFrame`):
   - Layout 11v11 from `homeFormation`/`awayFormation` + `homePitch/awayPitch`
     positions using the anchor table + duplicate-spread rule; mirror x for
     the team attacking left. Direction: first half home attacks right;
     flip at half time (`eventType === 'half_time'`).
   - Idle patrol: sinusoidal drift, amplitude ≤2 units, phase seeded from
     playerId (deterministic — `Math.sin(t * speed + playerId * 2.399)`
     style; no RNG state).
   - Possession bias from the latest timeline event: shift the in-possession
     team's outfield block +6 units toward attack, opponent −4; ball hovers
     in the corresponding zone (buildup/attack → attacking third center,
     `possession`/`commentary` → midfield of that team, `corner` → the corner
     arc on the attacking side, `foul` → midfield-ish point on the fouled
     team's side, `offside` → attacking third, then freeze briefly). All
     transitions lerp over ~1s of match-clock time. Unknown/no event →
     neutral midfield.
   - Red card / substitution: rebuild the layout from current
     `homePitch/awayPitch` (server truth) whenever those arrays change.
3. Clip splice: at a minute with `canonicalAnimations` (respect existing
   dedup/queue logic `animationsAtMinute`/`playAnimationsAtMinute` and the
   highlights-level gate), lerp 0.5s from ambient frame into clip frame 0,
   play the clip inline on the persistent canvas, lerp back. While a clip
   plays, minute advancement waits exactly as it does today for the modal.
4. Event markers: small transient icon at ball position for corner / foul /
   offside / card (2s fade).
5. Keep TEXT mode byte-identical to Slice B behavior.

Done when: build clean; PITCH_2D shows continuous plausible motion for a full
match on `/animation-preview` AND a real matchday; goals/saves play inline;
subs/red cards reflect within a minute; toggling views mid-match is safe;
TEXT mode unchanged.

### Slice D — replay zoom + retire the old modal

1. Remove the goal-anim modal's separate playback path (`#goalCanvas` markup
   :671–689 equivalent post-move, its 33ms timer if any remnant survived
   Slice B, and dead CSS).
2. Add "Replay" on recent moment entries (from `canonicalAnimations` /
   `keyMatchEvents`): presents the SAME `<app-match-pitch>` enlarged
   (CSS class on the container, e.g. fullscreen-ish overlay), replays the
   selected clip on the same clock, then returns to live ambient. Match-minute
   advancement pauses during replay (same rule as inline clips). No `/advance`
   or `/commit` calls from any replay/zoom/view code path.
3. Sweep for dead code from the old modal (`showGoalAnimation` flags, queue
   remnants) — delete, don't comment out.

Done when: build clean; no second canvas exists in the DOM at any time; replay
works during and after the match (post-match replay uses the cached
`LiveMatchData`).

## Guardrails (hard)

- Backend untouched. No `package.json` changes. No global CSS framework churn.
- Never break: interactive advance polling, substitution flow, localStorage
  resume, commit (including knockout/press-conference payload handling),
  multiplayer resume path, `animation-preview` route, highlights-level gating.
- Keep the existing code style (the FE uses plain components + services with
  BehaviorSubject; no NgRx, no standalone components, no signals).
- If something in the live flow behaves differently than described here, STOP
  and report the discrepancy instead of adapting the design.
- Do not commit to master; the user commits. Leave a short summary of files
  changed per slice.
