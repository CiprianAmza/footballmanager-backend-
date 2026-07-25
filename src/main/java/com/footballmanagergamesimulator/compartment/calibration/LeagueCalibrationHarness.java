package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeTeamInput;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure 20-club, home-and-away league harness used by the long baseline gate. */
public final class LeagueCalibrationHarness {
    private static final int CLUBS = 20;
    private static final int MATCHES_PER_SEASON = CLUBS * (CLUBS - 1);

    private final CompartmentEngineConfig compartment;
    private final MatchEngineConfig match;
    private final CanonicalScoreSampler sampler;
    private final CalibrationInputFactory inputFactory = new CalibrationInputFactory();

    public LeagueCalibrationHarness(CompartmentEngineConfig compartment, MatchEngineConfig match,
                                    CanonicalScoreSampler sampler) {
        this.compartment = Objects.requireNonNull(compartment, "compartment");
        this.match = Objects.requireNonNull(match, "match");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
    }

    public Result run(List<CalibrationTeam> rawTeams, int seasons, long seed) {
        if (rawTeams.size() != CLUBS) throw new IllegalArgumentException("calibration league must contain 20 clubs");
        if (seasons < 1) throw new IllegalArgumentException("seasons must be positive");
        CanonicalScoringWeightSet weights = CanonicalScoringWeightSet.baseline(compartment, match);
        CanonicalMatchEvaluationAdapter adapter = new CanonicalMatchEvaluationAdapter(
                weights.compartment(), weights.match());
        List<CanonicalRuntimeTeamInput> teams = new ArrayList<>(CLUBS);
        rawTeams.forEach(team -> teams.add(inputFactory.build(team, weights)));

        double pointsTotal = 0;
        double rankTotal = 0;
        for (int season = 0; season < seasons; season++) {
            int[] points = new int[CLUBS];
            int matchIndex = 0;
            for (int home = 0; home < CLUBS; home++) {
                for (int away = home + 1; away < CLUBS; away++) {
                    play(teams, points, home, away, adapter,
                            seed + (long) season * MATCHES_PER_SEASON + matchIndex++);
                    play(teams, points, away, home, adapter,
                            seed + (long) season * MATCHES_PER_SEASON + matchIndex++);
                }
            }
            pointsTotal += points[0];
            rankTotal += midRank(points, 0);
        }
        double averageRank = rankTotal / seasons;
        return new Result(seasons, seasons * MATCHES_PER_SEASON, pointsTotal / seasons,
                averageRank, (averageRank - 1.0) / (CLUBS - 1.0));
    }

    private void play(List<CanonicalRuntimeTeamInput> teams, int[] points, int home, int away,
                      CanonicalMatchEvaluationAdapter adapter, long seed) {
        CanonicalMatchEvaluation evaluation = adapter.evaluate(teams.get(home), teams.get(away), MatchVenue.HOME);
        CanonicalScoreSampler.GoalSample score = sampler.sample(evaluation, seed);
        if (score.homeGoals() > score.awayGoals()) points[home] += 3;
        else if (score.homeGoals() < score.awayGoals()) points[away] += 3;
        else {
            points[home]++;
            points[away]++;
        }
    }

    private static double midRank(int[] points, int candidate) {
        int better = 0;
        int equal = 0;
        for (int index = 0; index < points.length; index++) {
            if (index == candidate) continue;
            if (points[index] > points[candidate]) better++;
            else if (points[index] == points[candidate]) equal++;
        }
        return better + 1.0 + equal / 2.0;
    }

    public record Result(int seasons, int matches, double averagePoints,
                         double averageRank, double rankPercentile) { }
}
