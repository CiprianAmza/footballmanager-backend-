# Compartment Engine V1 Phase 13

Phase 13 introduces a single imported `compartment-scoring-weights-v1.yml` catalog and a pure calibration package. The catalog mirrors the Phase 12B values, keeps both rollout flags off, and supplies typed context rules to `ContextCoefficientMapper`.

`CanonicalScoringWeightCatalog` orders every bound leaf by stable path. `CanonicalScoringWeightSet` deep-copies the configuration pair before applying one override. `ScoringSensitivityHarness` uses only the canonical evaluation adapter and local score sampler, common seeds, and a fixed 38-match season schedule. Reports are written below `target/compartment-calibration` in deterministic CSV/JSON order.

The baseline, selected-weight, and all-weight 200-season gates are present as tests and are deliberately marked `NOT_RUN_BY_POLICY` in this implementation pass. No runtime formula, default, wiring, persistence path, or feature flag is changed.

The `SHADOW_STRIKER` role is available for measurement only. It is not added to default formations or automatic role selection.
