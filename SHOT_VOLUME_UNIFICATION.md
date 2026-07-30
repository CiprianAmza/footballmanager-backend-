# Shot volume — unification + odds-driven calibration

## Control

- Revision: 1 — proposal
- Owner: CODEX (implementation + calibration)
- Author: Claude (analysis + design)
- Status: OPEN
- Scope: one shot-volume model shared by the live engine and both stats
  projections; shot volume must reflect the pre-match matchup, not the scoreline.

## Problem

Three independent shot models exist today and none of them agree.

| Path | Formula | Typical per team |
|---|---|---|
| `LiveMatchSession` (watched match) | per-minute Bernoulli: `P(possession) × attackChance` | **6–8** |
| `MatchStatsService.generateCanonicalMatchStats` (Compartment V1, `enabled: true`) | `Poisson(10.5 + edge×7 ± 1.25)`, cap 35 | **9–12** |
| `MatchStatsService.generateMatchStats` (legacy / friendlies) | `Poisson((5 + share×15 + mentality) × logN × logN)`, cap 40 | **11–15** |

Consequences:

1. The same fixture reports a different shot count in the live view than in the
   persisted `MatchStats`.
2. The live model cannot express dominance at all. Its non-attack branch mass is
   a constant `0.38 + 0.10 + 0.04 = 0.52` regardless of team quality
   (`LiveMatchSession.java:1172`), attack chance is floored at `0.04` and capped
   at `0.22` (`:782`), and possession is clamped to `0.65` (`:781`). Best case is
   `93 × 0.65 × 0.18 + 4 ≈ 15` versus `93 × 0.35 × 0.04 + 1 ≈ 2`, so a 20–3 line
   is the extreme edge of the support and 23–1 is outside it.
3. Volume is coupled to the scoreline. In pinned mode every forced goal minute is
   also a shot, so a 1–1 between mismatched sides converges toward a symmetric
   shot line — the opposite of what the odds imply.
4. Binomial per-minute sampling is thin-tailed (SD ≈ 2.5 shots). Real shot counts
   are over-dispersed; without a per-match multiplier the tails cannot exist.
5. Corners and blocked shots are counted as shots — `homeShots++` fires on entry
   into the attack branch (`LiveMatchSession.java:1225`), before the outcome roll.

## Target behaviour

Shot volume is a function of the **pre-match matchup only** (control share +
tactics + per-match noise). The scoreline never feeds back into it, except as the
floor required by the football invariant `shots ≥ shotsOnTarget ≥ goals`.

A heavy favourite that draws 1–1 must still show a lopsided shot line.

## Proposed model — `ShotVolumeModel`

One class, config-driven, deterministic given `(seed, inputs)`. Single source of
truth for all three paths.

```
input:  controlShare s ∈ (0,1), homeTactic, awayTactic, Random rng
output: record ShotVolume(double homeMean, double awayMean, int homeShots, int awayShots)
```

```
totalMean = shotsTotalBase × logNormal(0, tempoSigma)          // shared match tempo
w         = s^k / (s^k + (1-s)^k)                              // odds → share of shots
homeMean  = totalMean × w       × logNormal(0, teamSigma) + mentalityBonus(home)
awayMean  = totalMean × (1 - w) × logNormal(0, teamSigma) + mentalityBonus(away)
shots     = clamp(Poisson(mean), goals, shotsMax)
```

`s` is the **control share**, not the win probability. It must be the same
quantity the goal model already consumes:

- Compartment V1 on → `effAtt1 / (effAtt1 + effDef2)` style matchup ratio
  (`LiveMatchSession.java:773`), so tactics and the attack-vs-defence axis feed
  the shot line the same way they feed goals;
- flag off → the scalar power share.

### Recommended starting parameters

| Key | Value | Rationale |
|---|---|---|
| `shots-total-base` | `24.0` | Real football: ~12–13 shots per team. |
| `split-exponent k` | `2.4` | Separation, see table below. |
| `tempo-sigma` | `0.22` | Shared open/closed-game factor (already used by the legacy path). |
| `team-sigma` | `0.28` | Per-side over-dispersion; this is what creates the tails. |
| `shots-max` | `40` | |

Separation produced by `k = 2.4`:

| control share `s` | shot split | line at total 24 |
|---|---|---|
| 0.50 | 50 / 50 | 12 – 12 |
| 0.55 | 59 / 41 | 14 – 10 |
| 0.60 | 68 / 32 | 16 – 8 |
| 0.65 | 76 / 24 | 18 – 6 |
| 0.70 | 83 / 17 | 20 – 4 |
| 0.75 | 88 / 12 | 21 – 3 |

With the two lognormal multipliers on top, `23–1` becomes a genuine tail event at
`s ≥ 0.70` instead of being impossible. **Open calibration question for Codex:**
`k = 2.4` is tuned so a fixture whose win probability is ~80% lands near a 3–4×
shot ratio. Confirm the mapping *win probability → control share* in the current
Compartment engine before freezing `k` — an 80% win probability is roughly a
0.62–0.66 control share in football, not 0.80, and picking `k` against the wrong
input would overshoot badly.

## Wiring

1. **New** `ShotVolumeModel` + `ShotVolumeProfile` (versioned record, same pattern
   as `CanonicalMatchStatsProfileV1`), parameters in `application.yml` under
   `match.engine.stats.shot-volume`.
2. `generateCanonicalMatchStats` — replace the `shotsBase + edge × shotsPowerScale`
   block (`MatchStatsService.java:318-322`) with a `ShotVolumeModel` call seeded
   from `CanonicalMatchStatsSeed`. Note the current formula uses the **centred**
   `edge` where the legacy path uses the **share**; that alone is why the two
   paths differ by ~40% in volume.
3. `generateMatchStats` — same call, replacing `MatchStatsService.java:129-141`.
   The lognormal machinery there is the model this proposal generalises, so this
   path changes least.
4. `LiveMatchSession` — the significant change. Draw the per-team shot budget once
   in the constructor (same place and pattern as `homeGoalMinutes` /
   `team1BigChanceMinutes`, `:797-829`), then schedule that many attack minutes per
   team. The per-minute branch roll stops being the source of shot volume and
   becomes only the *flavour* selector (goal / save / miss / block). Forced goal
   minutes must be drawn from the scheduled shot minutes so a pinned goal never
   adds an unbudgeted shot.
5. `persistLiveMatchStats` already copies the live counters through, so once (4)
   lands the live view and the persisted stats agree by construction.
6. Stop counting corners as shots — move the `homeShots++` at
   `LiveMatchSession.java:1225` into the goal / save / miss / blocked branches only.

## Invariants to keep

- `shots ≥ shotsOnTarget ≥ goals` per side (`validateGeneratedCanonicalStats`
  already enforces it; the Poisson draw must be floored by goals, never the
  reverse).
- Live counters == persisted `MatchStats` for the same fixture.
- Determinism: same `(fixtureKey, seed)` → same shot line, before and after a
  restart or a manual substitution.
- Shot volume must not change when only the scoreline changes. Worth a direct
  test: same seed and same powers, two different pinned scorelines → identical
  shot counts.

## Suggested validation

- Distribution harness over ≥1000 simulated fixtures: histogram of shots per team
  and of the shot *ratio*, bucketed by control share. Target: mean ≈ 12–13 per
  team overall, and at `s ≥ 0.70` at least a few percent of fixtures above a 6×
  ratio.
- Regression: `LeagueOutcome3IT` and the other league-outcome ITs assert on
  aggregate season stats and will need rebaselining.

## Open questions for Codex

1. The `win probability → control share` mapping above — needs confirming against
   the live Compartment V1 numbers before `k` is frozen.
2. Should tempo/mentality (`Attacking`, `Counter`, `Keep Ball`) shift the *total*
   or only the *split*? The legacy path adds a flat mentality bonus to each side's
   mean, which does both; a counter-attacking underdog arguably should take fewer
   but higher-quality shots, which argues for coupling mentality to the
   shots-on-target rate rather than to volume.
3. Whether `shotsMax = 40` and the `0.65` possession clamp should stay once volume
   no longer derives from possessed minutes.
