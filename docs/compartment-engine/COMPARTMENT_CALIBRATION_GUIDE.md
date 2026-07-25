# Compartment Engine calibration guide

This is the final calibration workflow for the canonical scoring engine. It is
not another product phase and it does not change production weights by itself.

## What is measured

The runner reads every active numeric leaf from the same bound configuration
used by the engine. The manifest records every path, baseline value, category,
consumer and the SHA-256 of both configuration files.

The calibration has two deliberately different levels:

1. **All-weight paired sweep** — every active weight is changed by each
   configured percentage. Baseline and tested simulations use identical seeds
   over 200 38-match seasons. This cheaply ranks the influence of the complete
   catalog and reports the points delta per one-percent weight change.
2. **Full league validation** — explicit weights plus the top-ranked weights
   are run through 200 complete 20-club, home-and-away seasons (76,000 matches
   per baseline or tested run). It writes every final table, average/median and
   standard deviation of points, average position, top-1/top-4/top-6/top-10 and
   bottom-three frequencies.

The baseline candidate is selected as a middle-strength team in a symmetric
20-club strength distribution. Around 60 points may be an observed result, but
it is never an assertion or a configured target; average position and finish
frequencies determine whether the team is actually mid-table.

## Configuration

Edit [`config/compartment-calibration.yml`](../../config/compartment-calibration.yml).
This experiment file is separate from the production values in
[`compartment-scoring-weights-v1.yml`](../../src/main/resources/compartment-scoring-weights-v1.yml).

Important fields:

- `fast-sweep.seasons` and `fast-sweep.percentages`: sample size and percentage
  changes applied to all active weights;
- `full-league.seasons` and `full-league.percentages`: complete-league sample
  and the percentage grid;
- `full-league.selected-weights`: exact paths that always receive the expensive
  full-league run;
- `full-league.top-weight-count`: additional paths selected from the fast sweep;
- `finish-buckets`: table-position thresholds included in the report.

To try a different value without changing production, edit only the experiment
percentage grid. To make a real production change after reviewing the report,
edit the corresponding exact path in `compartment-scoring-weights-v1.yml`, then
run the calibration again. The old and new manifests make the inputs auditable.

## Run

```bash
mvn verify -Pcalibrate
```

The profile runs only `CompartmentCalibrationReportIT`; it skips the ordinary
unit and integration suites. An alternative experiment file can be supplied:

```bash
mvn verify -Pcalibrate \
  -Dcompartment.calibration.config=/absolute/path/my-calibration.yml
```

## Output

Default directory: `target/compartment-calibration/final/`.

- `report.html` — readable overview and ranked impact tables;
- `run-manifest.json` — commit, seeds, configuration hashes and all baseline weights;
- `active-weights.csv` — complete editable/reference catalog;
- `weight-impact.csv` / `.json` — all-weight sweep and normalized points impact;
- `baseline-standings.csv` — all 4,000 baseline table rows for 200 seasons;
- `baseline-team-summary.csv` — aggregate table metrics for all 20 teams;
- `full-league-impact.csv` / `.json` — points, position and finish-frequency deltas;
- `full-league-standings/` — every baseline and tested season table for every
  expensive experiment.

Rows which cannot move farther in a requested direction because the baseline is
already at a legal boundary are retained with `status=SKIPPED_BOUND`. Genuine
errors use `status=FAILED` and include the exact validation error. Nothing is
silently dropped.
