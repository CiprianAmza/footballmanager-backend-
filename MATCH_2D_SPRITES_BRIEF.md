# Sprites round brief — animated player figures on the match pitch

Repo: /Users/ciprian.amza/Downloads/footballmanager-frontend-test (Angular 15,
no new npm deps, Canvas 2D only). Builds directly on the 2.5D round: the
renderer is `src/app/live-match/match-pitch.component.ts` + the projector in
`src/app/live-match/pitch-projection.ts` (`PitchStyle: 'classic' | 'broadcast'`,
`project(x,y) -> {px,py,scale}`; every draw site already goes through it).

## Goal

Replace the numbered discs with small animated player figures (the pixel-art
match vibe), **procedurally drawn on canvas** — there is no asset pipeline and
none may be added. Discs remain available as a third style.

## What to implement

1. **Style becomes three-valued**: `pitchDetail: 'discs' | 'sprites'` as a new
   independent toggle (localStorage `fm_pitchDetail`, default `discs`), stacked
   with `pitchStyle` (classic/broadcast) — all four combinations work.
2. **Procedural sprite**: a compact figure (~14-20 px tall at scale 1, scaled
   by the projector): head (skin tone circle), torso in kit primary via the
   existing `kitColor()` (GK uses gk colors), shorts in kit secondary/border,
   two legs. Subtle 1px outline for readability on green. Shirt number drawn on
   the torso (small, `numberColorFor` contrast rule). Name labels + collision
   suppression unchanged.
3. **Facing + run cycle derived from movement** (Codex rev. 8: derive from
   consecutive positions; never extend the backend contract):
   - Per player, keep the previous painted position; velocity vector gives
     facing (quantize to 8 directions) and speed.
   - Speed ≈ 0 → idle pose (legs together, tiny breathing bob optional).
   - Moving → 4-phase run cycle (legs alternate, slight lean into direction),
     phase advanced by distance traveled, not wall time, so speed changes read
     naturally and pauses freeze the cycle.
   - Ball carrier: ball drawn at the feet in facing direction (the ball's own
     draw already handles position; just avoid double-drawing — keep the
     existing ball rendering, sprite feet simply animate).
4. **Broadcast mode**: sprites scale with depth via the projector, drop shadow
   ellipse stays under the feet, painter's order already sorts by depth.
5. **Cards/state accents**: keep any existing accents (scorer highlight, ball
   carrier glow) working for sprites.
6. **Performance**: 22 sprites at 60fps must stay cheap — pre-render each
   (kit x facing x phase) combination to small offscreen canvases on first use
   and cache per match (kits change at most per clip); drawImage per frame, no
   per-frame path drawing for bodies.

## Constraints

- `discs` mode stays pixel-identical. No backend, no service, no model changes.
- All existing features work in every combination: goal clips, splice blends,
  ambient, markers, event text, confetti, replay zoom, style switching
  mid-clip.
- `npx ng build` clean; do not materially worsen the preexisting bundle-budget
  overage; no angular.json changes.
- Verify in browser with synthetic frames driven through `renderFrame()` (same
  stub technique as prior rounds): show idle vs running, 8 facings, both
  pitch styles, both teams + GKs. Describe (or capture) the verification.
- Do NOT commit; leave changes in the working tree. Report files changed, the
  sprite cache design, and any compromises.
