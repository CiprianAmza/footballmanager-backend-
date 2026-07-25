# Compartment Engine V1 Phase 10: bounded shadow calibration

Phase 10 collects bounded, in-memory observations from the existing Phase 9 shadow evaluation.
The accumulator is synchronized, keeps only O(1) aggregate state, and never stores the observation
objects. Each record is prepared and validated locally, then committed as one state transition;
an invalid sample cannot change any aggregate or initialize a histogram. Snapshots defensively copy
their ordered histograms and are immutable to callers. Production has exactly one Spring singleton
accumulator; tests provide it explicitly.

The accumulator records legacy means, canonical expected goals, outcome rates, multiclass Brier
score, logarithmic loss, favorite/upset measures, goal histograms, four team segments, and total
evaluation duration. The canonical goal histogram is the convolution of the two canonical goal
PMFs. Legacy goals above the configured cap are placed in the final bucket.

Segments are home and away team samples classified by defensive mentality
(`DEFENSIVE` or `VERY_DEFENSIVE`) and by whether any player has `STAY_FORWARD`. Segment means use
the number of teams in that segment. These are observational diagnostics, not causal estimates.

The recommended readiness thresholds are 10,000 samples, maximum mean home and away goal deltas
of 0.20, maximum outcome-rate delta of 0.04, maximum upset-rate delta of 0.04, and maximum
multiclass Brier score of 0.24. Below the minimum sample count the evaluator reports
`INSUFFICIENT_DATA`; otherwise it reports `PASS` or `FAIL` with deterministic violation order.
`minimumSamples` must be strictly positive.

Calibration is passive and does not change runtime scoring, RNG, MatchPlan, persistence, flags, or
frontend behavior. The existing shadow feature flag remains the gate; when it is off, no request is
built and no observation is recorded. No Phase 11 work has started.
