# Round 1 self-verification brief — 2D match view vs master

You are auditing the Round 1 implementation (Faza 0 + Faza 1 of the 2D match
view). The work sits as UNCOMMITTED changes in
`/Users/ciprian.amza/Downloads/footballmanager-frontend-test/` on top of `main`
(commit `c186595`). Your job: prove that nothing from master was silently lost
or broken by the extraction, and that the implementation matches its binding
brief. **This is a read-only audit — do not modify any file. Report findings.**

## Why this audit exists (calibration — two real regressions of this class)

1. An earlier dedup commit (`50d795b`) silently dropped the fast-forward stats
   block (Calendar / Seasons completed / Days processed / Elapsed) when merging
   two similar templates. Restored later by hand.
2. The extracted pitch renderer assigned raw dataset color names to
   `ctx.fillStyle`. `"lila"` is not a valid CSS color; canvas silently keeps
   the previous fillStyle, so one team rendered as a mix of white/black/orange
   discs. Fixed via `kitColor()` normalization in `match-pitch.component.ts`.

Both are the same failure mode: **a large mechanical move/merge where a detail
of the original was dropped or subtly changed**. Find the remaining ones.

## Scope

Round 1 files only:
- Modified: `src/app/app.component.ts|html|css`, `src/app/app.module.ts`,
  `src/app/app-routing.module.ts`, `src/app/animation-preview/*`
- New: `src/app/live-match/*` (component, match-pitch, ambient-synthesizer,
  playback-clock, spec), `src/app/models/live-match.model.ts`,
  `src/app/services/live-match.service.ts`

Out of scope (separate workstreams, ignore): `tactics*`, `tactic/`,
`match-ratings`, `media-prediction`, `player-face`, `face-lab/`, and the
fast-forward stats restore in `app.component.html` (already reviewed).

## Task 1 — Removal audit (the core)

For `app.component.ts`, `app.component.html`, `app.component.css`:

1. `git diff HEAD -- <file>` and enumerate every REMOVED unit:
   - TS: every removed method, getter, field, constant, import.
   - HTML: every removed template block (modals, buttons, bindings, *ngIfs,
     aria attributes, CSS-class bindings like `[class.running]`).
   - CSS: every removed rule.
2. Classify each removed unit as exactly one of:
   - **MOVED** — exists (possibly renamed) in the new live-match files. Name
     the destination file:line. Verify the move is faithful: same logic, same
     conditions, same magic numbers (intervals, delays, thresholds, storage
     keys, colors). Flag any silent change.
   - **REPLACED** — intentionally superseded by a new mechanism (e.g. the
     three `setInterval`s → PlaybackClock; the goal-anim modal → replay zoom).
     Verify the replacement covers every behavior of the original, including
     edge cases (pause/resume points, cleanup in ngOnDestroy, AfterViewChecked
     canvas-ready hack, localStorage keys `fm_liveMatchKey` /
     `fm_liveMatchInteractive` / `fm_matchHighlightsLevel`).
   - **LOST** — no equivalent exists. This is a finding.
3. Special attention (known-risk areas of this extraction):
   - `ngOnDestroy` cleanup parity: everything the old AppComponent tore down
     (timers, subscriptions, keydown handlers) must be torn down by someone.
   - Keyboard shortcuts / HostListeners that lived on AppComponent.
   - The synthetic extra-time/penalties block (fields :98–123 in the old file,
     `tickSynthetic`, `beginSyntheticShootout`) — moved intact?
   - Suspense logic (`isSuspenseShot`/`applyShotSuspense`) — delays and
     conditions identical?
   - Multiplayer resume path (`resumeMultiplayerLiveMatch`) and the
     `maybeResumeLiveMatch` refresh path — still wired from AppComponent into
     the new component with the same triggers?
   - Every CSS rule that the moved HTML still references — present in
     `live-match.component.css` (or the component's inline styles)? A class
     used in the new template with no rule anywhere = LOST styling.

## Task 2 — Contract compliance (the binding decisions)

Verify in code, citing file:line:
1. Exactly ONE canvas in the DOM at any time (search all templates for
   `<canvas`).
2. Replay / zoom / view-switch / frame generation never call `/advance` or
   `/commit` (trace every call site of `LiveMatchService.advance|commit`).
3. One clock: no `setInterval`/`setTimeout` driving playback (setTimeout for
   one-shot UX like suspense/event-text fade is acceptable — but confirm each
   one pauses/cleans correctly).
4. The RAF loop runs outside Angular and re-enters the zone only for
   template-state changes.
5. `matchViewMode` only in localStorage (`fm_matchViewMode`), default TEXT.
6. `<app-match-pitch>` consumes only the normalized frame model — no transport
   DTO leaks into the renderer beyond the adapter functions.
7. Kit colors: every sink of kit color strings (canvas fillStyle/strokeStyle,
   CSS style bindings) goes through `kitColor()`/`safeColor()` — sweep for any
   remaining raw usage in Round 1 files.

## Task 3 — Master-side behavior spot-checks

For each, compare old vs new implementation line-by-line and report identical /
changed (changed = finding unless obviously a fix):
1. `getSpeedInterval` values (600/300/100/40) and where speed changes take
   effect mid-minute.
2. `advance` polling: guard flag, target-minute formula
   `min(currentMinute+1, totalMinutes)`, wholesale replacement of
   `liveMatchData`, `liveCurrentIndex` reset.
3. Substitution flow: modal pause/resume, POST body, state merge, counters.
4. Commit flow: idempotence guard, knockout text, press-conference id chain,
   `teamService.notifyRefresh()`.
5. Highlights gating (`shouldPlayAnimation` × `fm_matchHighlightsLevel`) and
   the dedup/queue logic (`animationsAtMinute`, `playAnimationsAtMinute`,
   `animationKey`, pending queue).
6. `animation-preview` route still exercises the same engine data end-to-end.

## Task 4 — Build gate

`npx ng build` in the frontend repo must produce zero TS/template errors from
Round 1 files. The bundle-budget error is preexisting — measure only that the
overage isn't materially worse (~205 kB over was the last reading).
Do NOT run test suites.

## Report format (final message)

1. **LOST** findings first (each: what, where it was on master, impact,
   suggested restore point) — the reason this audit exists.
2. **CHANGED-IN-MOVE** findings (silent behavior drift during MOVED/REPLACED).
3. Contract compliance table (7 checks, pass/fail + evidence).
4. Behavior spot-check table (6 checks).
5. Counts: removed units audited / MOVED / REPLACED / LOST.
Findings must cite file:line on both sides. No code changes — report only.
