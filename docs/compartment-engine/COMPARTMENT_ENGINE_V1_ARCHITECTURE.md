# Compartment Engine V1 — architecture and phased contract

## 1. Status and scope

This document defines the target V1 match-decision architecture and the strictly limited Phase 0/1
delivery. The current delivery is deliberately inert at runtime:

- `match.engine.compartment.enabled=false` in production YAML;
- no call from `TacticalScoreService`, `MatchRoundSimulator`, `LiveMatchSession`, `MatchPlan`,
  `MatchSimulationService`, Fast Forward, knockout or any controller;
- no random sampling in the new package;
- no database entity, repository, migration, endpoint or frontend change;
- the current `TacticalScoreService` remains the production tactical scorer;
- the committed baseline characterizes the current engine and is not evidence that V1 is calibrated.

Phase 0/1 provides the typed coefficient catalogue, pure contextual-rating/breakdown functions,
pure previews of the contracted mentality, defensive-exposure and probability formulas, this
architecture, and a reproducible current-engine snapshot. Every runtime or persistence change is a
later review gate.

## 2. Non-negotiable architectural decision: one engine

V1 will not create a permanent second scoring engine. `TacticalScoreService` is the current
authoritative tactical scoring boundary. In the canonical-integration phase it must either:

1. be evolved to own the compartment pipeline; or
2. delegate to one extracted, package-level `MatchDecisionCore` that is also the only decision core
   used by live, instant, AI-vs-AI, Fast Forward and knockout execution.

The Phase 0/1 classes under `compartment` are pure mathematical building blocks, not a parallel
runtime service. There is intentionally no `@Service` scorer in that package.

The end-state decision flow is:

```text
immutable fixture + lineup + tactics + canonical seed
                    |
                    v
      TacticalScoreService / one extracted core
                    |
                    v
 MatchDecision(score90, extra time, shootout, xG, explanations)
                    |
                    v
        MatchPlan persists the decision once
                    |
        +-----------+-----------+-----------+
        |           |           |           |
       live       instant    AI/Fast FF   knockout
        |           |           |           |
        +-----------+-----------+-----------+
                    |
        events -> scorers/assists/stats/UI
```

Execution paths consume the decision. They do not calculate or reinterpret it. Scorers and assists
are projections of canonical events only.

## 3. Boundaries

### 3.1 Snapshot adapter boundary

A future adapter will copy mutable JPA/game objects into an immutable input snapshot containing:

- player ID and configured position;
- natural/used position familiarity;
- role and duty;
- the relevant 1–20 attributes;
- fitness, morale and role suitability;
- tactical and player instructions;
- traits;
- team mentality and fixture context.

No pure formula accepts an entity, repository, lazy collection or mutable tactic object. Phase 0/1
already follows this rule through `PlayerRatingInput`.

### 3.2 Pure player-rating boundary

`ContextualPlayerRatingCalculator` produces separate Attack, Midfield and Defense results. Each
result exposes every attribute contribution and every post-attribute multiplier. It has no score,
opponent, random source or persistence concern.

Stable enums are used for attributes, roles, duties and compartments. Display strings are adapted
at the boundary rather than used as configuration keys. This avoids Spring/YAML relaxed-binding
changes to keys containing spaces or hyphens.

### 3.3 Aggregation boundary (future)

`TeamCompartmentAggregator` will aggregate player breakdowns, apply behavior/coverage and allocate
Midfield according to mentality. It will be the only producer of final team Attack (`AT`) and
Attack Protection (`AP`) values. The Phase 0/1 `CompartmentMath` method is only a pure contract
preview; it is not a team aggregator and is not wired.

### 3.4 Probability boundary (future)

The probability model consumes only final `AT1/AP1/AT2/AP2`, openness, home/neutral venue and the
canonical seed. The Phase 0/1 `GoalProbabilityFormula` computes xG and the analytic predictive
distribution without sampling. Runtime Gamma/Poisson draws remain unauthorized.

### 3.5 Canonical decision and execution boundary (future)

The decision object should be immutable and versioned. At minimum it will contain:

- engine/config version and a reproducible config fingerprint;
- canonical seed and fixture key;
- home/away AT/AP and matchup shares;
- home/away xG and 5–95% goal intervals;
- 90-minute goals;
- extra-time goals, when applicable;
- shootout winner/result, explicitly separate from goals;
- player and team explanation breakdowns.

`MatchPlan` persists or reuses this result under the existing idempotency boundary. Refresh,
restart and retry load it; they never draw again.

## 4. Phase 0/1 typed configuration

The complete initial catalogue is under `match.engine.compartment` in
`src/main/resources/application.yml` and binds to `CompartmentEngineConfig`.

The catalogue includes:

- feature flag, attribute domain, context clamps, total-context clamps and player-condition factors;
- Attack/Midfield/Defense attribute weights;
- a goalkeeper-specific Defense attribute profile;
- all current player positions, roles and duties;
- all five mentality splits/transfers/openness values;
- `REFUSES_DEFENSIVE_WORK`, Stay Forward and Track Back behavior;
- exposure zone weights, DM/CB coverage and nonlinear protection penalty;
- matchup exponent, home advantage, Gamma shape, goal cap and extra-time scale.

All fixed coefficients used by the pure V1 formulas come from this typed object. Formula classes do
not contain gameplay weights. Constructor defaults make missing development properties safe, while
the production YAML is the committed initial candidate and its completeness is tested.

The flag is configuration only in this phase. Binding a false property is not runtime wiring.

## 5. Contextual player rating

For an attribute `attr` in the valid domain 1–20:

```text
N(attr) = (attr - 1) / 19
Kused   = clamp(Krequested, configured K min, configured K max)
factor  = clamp(1 + Kused * (2 * N(attr) - 1), 0.60, 1.40)
```

For each compartment `C`:

```text
baseC       = 100 * sum(weightC,attr * N(attr))
rawContextC = 100 * sum(weightC,attr * N(attr) * factor(attr))
contextRate = clamp(rawContextC / baseC, 0.70, 1.30)
contextC    = baseC * contextRate
roleFit     = 0.85 + 0.30 * clamp(suitability, 0, 100) / 100

finalC = contextC
       * positionCompartmentMultiplier
       * roleCompartmentMultiplier
       * dutyCompartmentMultiplier
       * familiarity
       * max(fitnessFloor, fitness / 100)
       * (1 + (morale - moraleNeutral) * moraleSlope)
       * roleFit
```

The configured attribute weights for each selected profile sum to 1. Position, role and duty do not
silently mutate attributes; they are reported as separate factors. The breakdown keeps both raw and
clamped context totals so a UI or calibration report can explain a clamp.

Context coefficient production mapping is intentionally absent in Phase 0/1. Later tactical and
instruction adapters may set `K` only for relevant attributes. Unrelated contributions must remain
at `K=0`, so a tactic cannot globally boost a player.

## 6. Mentality and compartment redistribution contract

Midfield is split between Attack and Defense, then the configured line transfer is applied while
conserving total value:

| Mentality | Midfield to A/D | Transfer | Openness |
|---|---:|---:|---:|
| Very Attacking | 90% / 10% | 20% Defense to Attack | 1.15 |
| Attacking | 70% / 30% | 8% Defense to Attack | 1.07 |
| Balanced | 50% / 50% | none | 1.00 |
| Defensive | 25% / 75% | 8% Attack to Defense | 0.90 |
| Very Defensive | 10% / 90% | 20% Attack to Defense | 0.78 |

The later aggregator must also implement the contracted approximately 20% transfer toward the
extremes/wide channels. That spatial allocation is not implemented in Phase 0/1 because it requires
team shape and lineup zones.

## 7. Traits, defensive engagement and nonlinear protection contract

Behavior precedence is trait first, then instruction:

| Behavior | Engagement | Attack multiplier | Other |
|---|---:|---:|---|
| `REFUSES_DEFENSIVE_WORK` | 0.08 | 1.15 | ignores defensive instructions; morale -3 if forced defensive |
| Stay Forward | 0.30 | 1.08 | no forced morale effect |
| Default | 1.00 | 1.00 | neutral |
| Track Back | 1.15 | 0.95 | can contribute more than neutral coverage |

Exposure and compensation are:

```text
E        = sum(zoneWeight * (1 - engagement))
Coverage = bestDM + 0.55 * secondDM + min(CB recovery pace, 0.50)
R        = max(0, E - 0.65 * Coverage)
APfinal  = AP * exp(-0.55 * R^1.70)
```

All DM and CB inputs are normalized before this formula. The negative exponential is deliberately
nonlinear: one disengaged forward can be covered, while multiple disengaged players can cross the
residual-risk threshold sharply. The special one-versus-three-refuser outcome is a future
calibration target, not a Phase 0/1 result.

The persistent trait model is not created yet. A later extensible `player_trait` table should use a
stable trait code, effective dates/versioning and a unique player/code key. Editor/admin support is
part of that later gate; no frontend work is included here.

## 8. Matchup, xG and predictive distribution contract

For each direction:

```text
q1 = AT1^1.5 / (AT1^1.5 + AP2^1.5)
q2 = AT2^1.5 / (AT2^1.5 + AP1^1.5)

home xG = openness * q1 * 1.08
away xG = openness * q2
```

The Phase 0/1 analytic preview treats each goal count as Gamma–Poisson with Gamma shape 12. Its
closed form is a negative-binomial predictive distribution. Probability above the configured goal
cap 7 is folded into the cap cell, and discrete 5%/95% quantiles form the displayed interval.

Runtime sampling is deferred. When authorized, it will draw one Gamma rate and then one Poisson
count per side from the canonical seed. Extra time is a separately named 30-minute decision at
approximately one third of normal openness. Shootout attempts/winner are separate facts and never
inflate the football score or scorer tally.

## 9. Canonical integration and idempotency plan

The integration phase must satisfy all of the following in one change set:

1. `TacticalScoreService` (or its single extracted core) returns exactly one immutable decision.
2. `MatchPlan` receives the score/minute decision; execution paths do not call a scorer again.
3. Live, instant, AI-vs-AI, Fast Forward and knockout use the same plan semantics.
4. Canonical goal events are resolved from the persisted goal slots and on-pitch lineup.
5. `Scorer`, assists, match stats and UI facts are projections of canonical events.
6. A committed plan is immutable and retry is a successful no-op.
7. Concurrent creation has one winner; the loser reloads the persisted plan.
8. Flag OFF executes the current path. Flag ON executes the compartment path. No hybrid result is
   permitted within one fixture.
9. Rollback is configuration-only: switch the flag off. Old persisted plans remain replayable by
   engine/config version.

Until that gate, the new configuration and formulas must remain unreachable from runtime.

## 10. Current-engine baseline

The committed artifact is
`src/test/resources/compartment-engine/current-tactical-engine-baseline.json`. The verifier is
`CurrentTacticalEngineBaselineTest`.

Methodology:

- exact backend base `49664fb2d56f6e6b99e25abfe882f88d4c1df392`;
- current `TacticalScoreService` with plain `MatchEngineConfig` defaults;
- 20,000 matches per scenario;
- base seed `2026072301`; each next scenario adds `1,000,003`;
- one continuous Java `Random` stream per scenario;
- current capped-Poisson sampling, not the future Gamma–Poisson preview;
- synthetic immutable `TeamProfile` and explicit tactic vectors;
- exact counts plus rates for W/D/L, goals, 0-0, six-or-more goals and goal-cap hits.

Snapshot summary:

| Scenario | Goals/match | Home win | Draw | Away win | 0-0 | 6+ goals |
|---|---:|---:|---:|---:|---:|---:|
| Equal, balanced | 2.8788 | 39.475% | 25.130% | 35.395% | 5.750% | 7.245% |
| Home 30% stronger | 2.9433 | 54.400% | 22.865% | 22.735% | 5.175% | 8.100% |
| Home 30% underdog | 2.88775 | 27.595% | 23.315% | 49.090% | 5.490% | 7.380% |
| Equal, home Very Defensive | 1.52505 | 33.445% | 36.260% | 30.295% | 21.785% | 0.455% |
| Equal, home Very Attacking | 3.70225 | 35.690% | 21.870% | 42.440% | 2.385% | 17.015% |
| Equal, high line/press vs direct | 4.71865 | 42.615% | 18.900% | 38.485% | 0.770% | 33.920% |

These numbers describe the old engine only. A future change to that engine should fail this exact
characterization test until the artifact change is reviewed. The test can print a candidate with
`-Dcompartment.baseline.print=true`, but it never rewrites the artifact automatically.

## 11. Calibration targets — contracted, not validated

The later calibration harness must report, without silently rewriting production configuration:

- equal teams: 2.7–3.2 goals/match;
- home win advantage: +3–6 percentage points;
- draws: 22–30%;
- 0-0: 5–11%;
- matches with at least six goals: at most 8%;
- a 30% contextual advantage: 75–86% wins;
- outsider wins: 6–15%;
- Very Defensive: opponent xG reduced 18–35% and draws increased by at least 6pp;
- no tactic optimal for more than 60% of archetypes;
- strong team with one `REFUSES_DEFENSIVE_WORK` player plus an excellent DM: 18.5–19.5 wins per
  20 matches;
- the same team with three such players: at most 12 wins per 20 matches, measured across 200 series.

None of these is marked PASS by Phase 0/1. The baseline table is not substituted for the future
200x20 scenario harness and the pure formulas are not tuned results.

## 12. Calibration harness and artifacts (future gate)

The authorized future harness will run 200 series of 20 fixtures per scenario with explicit seed
lists, team archetypes and configuration fingerprints. It will produce:

- `candidate-compartment-engine.yml` — a reviewable candidate only;
- `calibration-report.json` — inputs, seeds, aggregate distributions, confidence intervals, target
  verdicts and source commit;
- a CI test that regenerates and compares the committed report/config values.

It must not edit `application.yml`, activate the flag, or call production databases. A report is
invalid if any result lacks its seed/config/version or if retries consume a different random path.

## 13. Explainability contract

The future API/UI will expose only data already present in the canonical decision:

- contextual player rating and raw base;
- per-attribute base/context contributions;
- position, role, duty, familiarity, fitness, morale and role-fit factors;
- team Attack/Midfield/Defense and final AT/AP;
- mentality redistribution;
- advantages, exposure, coverage, residual penalties and conflicts;
- matchup shares, xG and 5–95% goal intervals;
- traits and instructions that actually affected the decision.

No controller or frontend recalculates a rating from mutable current state after the match.

## 14. Delivery roadmap and gates

| Roadmap item | Deliverable | Status after Phase 0/1 |
|---|---|---|
| 1 | Contract, typed config, current-engine baseline, pure tests | delivered for review |
| 2 | ContextualPlayerRating A/M/D with explainable breakdown | pure foundation delivered; runtime adapter/wiring absent |
| 3 | Relevant-attribute tactic/instruction context, total 70–130% | config/clamp contract only; mapping not implemented |
| 4 | Persistent traits, Stay Forward/Track Back, nonlinear coverage, admin/editor | pure behavior formula only; persistence/API/UI absent |
| 5 | TeamCompartmentAggregator, mentality and wide redistribution, final AT/AP | pure aggregator delivered; runtime wiring still absent |
| 6 | Runtime GoalProbabilityModel, seeded Gamma–Poisson, ET/shootout | analytic preview only; no RNG/runtime |
| 7 | Canonical single-decision integration through MatchPlan | not implemented |
| 8 | 200x20 calibration harness and candidate/report artifacts | not implemented |
| 9 | Frontend explainability | not implemented |
| 10 | Three-season OFF/ON validation and gradual rollout | not implemented |

Each incomplete item requires separate ATLAS authorization and review. In particular, the presence
of pure preview functions does not authorize moving their outputs into a match path.

## 15. Validation and rollout plan (future)

After canonical integration and calibration pass, validation runs at least three seasons with the
same world inputs under flag OFF and ON. The report must compare score/xG distributions, home
advantage, draws, scoreless games, high-score tails, upset rates, tactic/archetype diversity,
knockout behavior and performance. Rollout begins with explicit configuration enablement in a
separate commit, retains the OFF fallback and stops on any canonical divergence or idempotency
failure.

## 16. Known risks and decisions deferred

- The initial attribute tables are candidate weights, not calibrated weights.
- Goalkeeper Defense has a dedicated profile; goalkeeper Attack/Midfield reuse the generic table
  behind low position multipliers until a later evidence-backed profile is approved.
- Position/role/duty multipliers can produce ratings above 100; V1 treats rating as a strength index,
  not a percentage. A UI scale is a separate presentation decision.
- Context `K` mapping from team/player instructions is deliberately missing.
- Zone assignment and the approximately 20% wide transfer require formation geometry and are
  deferred to aggregation.
- Trait persistence, historical versioning and editor permissions require a dedicated data/API gate.
- Gamma–Poisson sampling order and seed derivation must be reviewed together with MatchPlan; an
  isolated RNG added earlier would break deterministic retry.
- Configuration version/fingerprint persistence is required before flag ON, otherwise historical
  decisions cannot be explained after coefficients change.

## 17. Phase 0/1 completion checklist

- [x] architecture and canonical single-engine boundary documented;
- [x] typed YAML coefficient catalogue;
- [x] feature flag explicitly false;
- [x] pure contextual rating with A/M/D breakdown;
- [x] pure mentality, behavior/exposure and probability previews;
- [x] reproducible current-engine statistical artifact;
- [x] unit/characterization tests;
- [x] no runtime scoring or MatchPlan wiring in the Phase 0/1 diff;
- [ ] ATLAS independent review and approval.
