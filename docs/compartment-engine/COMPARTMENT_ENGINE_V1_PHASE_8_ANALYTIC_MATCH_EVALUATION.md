# Compartment Engine V1 Phase 8: Analytic Match Evaluation

Phase 8 defines a pure analytic match-evaluation boundary. It consumes two immutable
`CanonicalRuntimeTeamInput` values and a `MatchVenue`, evaluates each team through the existing
canonical team adapter, and returns a `CanonicalMatchEvaluation`.

## Inputs and Matchup

Each canonical team contributes only its evaluated `attack`, `attackProtection`, and `openness`.
The combined openness is exactly the arithmetic mean:

```text
combinedOpenness = (home.openness + away.openness) / 2
```

The home matchup uses home attack against away attack protection. The away matchup uses away
attack against home attack protection. `MatchVenue.HOME` applies the configured home-advantage
multiplier to home xG once. `MatchVenue.NEUTRAL` does not apply it.

## xG, PMF, and Outcomes

`GoalProbabilityFormula` calculates the two matchup shares and expected goals, then derives the
Gamma-Poisson predictive distributions analytically. The existing goal-cap bucket is included in
the distribution. Home win, draw, and away win are calculated directly from the two PMFs:

```text
homeWin = sum(P(home=i) * P(away=j)) for i > j
draw    = sum(P(home=i) * P(away=j)) for i == j
awayWin = sum(P(home=i) * P(away=j)) for i < j
```

The result contains probabilities only. It does not sample goals, produce a 90-minute score, use
RNG, clocks, persistence, repositories, or Spring runtime services.

## Scope and Limitations

The Phase 8 adapter is deterministic and does not mutate either canonical input. It does not
connect to `MatchRoundSimulator`, scoring callers, MatchPlan, frontend, persistence, or feature
flags. The compartment engine flag remains unchanged and OFF.

Phase 9 is a separate task for any read-only shadow wiring review. No Phase 9 wiring is included
here.
