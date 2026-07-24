# Compartment Engine V1 Phase 13

Phase 13 introduces a single non-optional imported `compartment-scoring-weights-v1.yml` catalog and a pure calibration package. The bound application profile currently exposes 407 stable leaf keys across rating, context, compartment, position, role, duty, mentality, work-rate, exposure, probability, player-value, role-fit, and instruction families. The catalog mirrors the Phase 12B values, keeps both rollout flags off, and supplies typed context rules to `ContextCoefficientMapper`.

`CanonicalScoringWeightCatalog` orders every bound leaf by stable path. `CanonicalScoringWeightSet` deep-copies the configuration pair before applying one validated override. `ScoringSensitivityHarness` rebuilds both canonical input pipelines from immutable raw fixtures, uses only independent canonical evaluation adapters and the local score sampler, common seeds, paired seasonal deltas, and a fixed 38-match season schedule. Reports are written below `target/compartment-calibration` in deterministic CSV/JSON order.

The baseline, selected-weight, and all-weight 200-season gates are executable tests enabled only by `compartment.calibration.long=true`; they were `NOT_RUN_BY_POLICY` in this implementation pass. No runtime formula, default, wiring, persistence path, or feature flag is changed.

The `SHADOW_STRIKER` role is available for measurement only. It is not added to default formations or automatic role selection.
