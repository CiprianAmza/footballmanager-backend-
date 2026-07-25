package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeTeamInput;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure detailed 20-club calibration league. Unlike {@link LeagueCalibrationHarness},
 * this runner retains every season table and derives finish-frequency summaries.
 */
public final class DetailedLeagueCalibrationHarness {
    private static final int CLUBS = 20;
    private static final int MATCHES_PER_SEASON = CLUBS * (CLUBS - 1);

    private final CompartmentEngineConfig compartment;
    private final MatchEngineConfig match;
    private final CanonicalScoreSampler sampler;
    private final CalibrationInputFactory inputFactory = new CalibrationInputFactory();

    public DetailedLeagueCalibrationHarness(CompartmentEngineConfig compartment,
                                            MatchEngineConfig match,
                                            CanonicalScoreSampler sampler) {
        this.compartment = Objects.requireNonNull(compartment, "compartment");
        this.match = Objects.requireNonNull(match, "match");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
    }

    public Result run(List<CalibrationTeam> rawTeams, int seasons, long seed,
                      List<Integer> topFinishBuckets) {
        return run(rawTeams, seasons, seed, topFinishBuckets,
                CanonicalScoringWeightSet.baseline(compartment, match));
    }

    public Result run(List<CalibrationTeam> rawTeams, int seasons, long seed,
                      List<Integer> topFinishBuckets,
                      CanonicalScoringWeightCatalog catalog,
                      CanonicalScoringWeightOverride override) {
        CanonicalScoringWeightSet weights = CanonicalScoringWeightSet
                .baseline(compartment, match)
                .override(Objects.requireNonNull(catalog, "catalog"),
                        Objects.requireNonNull(override, "override"));
        return run(rawTeams, seasons, seed, topFinishBuckets, weights);
    }

    private Result run(List<CalibrationTeam> rawTeams, int seasons, long seed,
                       List<Integer> topFinishBuckets,
                       CanonicalScoringWeightSet weights) {
        Objects.requireNonNull(rawTeams, "rawTeams");
        if (rawTeams.size() != CLUBS) {
            throw new IllegalArgumentException("calibration league must contain 20 clubs");
        }
        if (seasons < 1) throw new IllegalArgumentException("seasons must be positive");
        List<Integer> buckets = normalizeBuckets(topFinishBuckets);
        CanonicalMatchEvaluationAdapter adapter = new CanonicalMatchEvaluationAdapter(
                weights.compartment(), weights.match());
        List<CanonicalRuntimeTeamInput> teams = rawTeams.stream()
                .map(team -> inputFactory.build(team, weights))
                .toList();
        CanonicalMatchEvaluation[][] evaluations = precomputeEvaluations(teams, adapter);

        List<SeasonStanding> standings = new ArrayList<>(seasons * CLUBS);
        for (int season = 1; season <= seasons; season++) {
            MutableTeamSeason[] rows = new MutableTeamSeason[CLUBS];
            for (int team = 0; team < CLUBS; team++) rows[team] = new MutableTeamSeason(team);
            int matchIndex = 0;
            for (int home = 0; home < CLUBS; home++) {
                for (int away = home + 1; away < CLUBS; away++) {
                    play(evaluations[home][away], rows, home, away,
                            seed + (long) (season - 1) * MATCHES_PER_SEASON + matchIndex++);
                    play(evaluations[away][home], rows, away, home,
                            seed + (long) (season - 1) * MATCHES_PER_SEASON + matchIndex++);
                }
            }
            int seasonNumber = season;
            List<MutableTeamSeason> orderedRows = Arrays.stream(rows)
                    .sorted(Comparator.comparingInt(MutableTeamSeason::points).reversed()
                            .thenComparing(Comparator.comparingInt(MutableTeamSeason::goalDifference).reversed())
                            .thenComparing(Comparator.comparingInt(MutableTeamSeason::goalsFor).reversed())
                            .thenComparingInt(MutableTeamSeason::teamIndex))
                    .toList();
            for (int position = 0; position < orderedRows.size(); position++) {
                standings.add(orderedRows.get(position).toStanding(seasonNumber, position + 1));
            }
        }

        List<TeamSummary> summaries = summarize(standings, seasons, buckets);
        return new Result(seasons, seasons * MATCHES_PER_SEASON, seed, buckets,
                standings, summaries);
    }

    private static CanonicalMatchEvaluation[][] precomputeEvaluations(
            List<CanonicalRuntimeTeamInput> teams,
            CanonicalMatchEvaluationAdapter adapter) {
        CanonicalMatchEvaluation[][] evaluations = new CanonicalMatchEvaluation[CLUBS][CLUBS];
        for (int home = 0; home < CLUBS; home++) {
            for (int away = 0; away < CLUBS; away++) {
                if (home != away) {
                    evaluations[home][away] = adapter.evaluate(
                            teams.get(home), teams.get(away), MatchVenue.HOME);
                }
            }
        }
        return evaluations;
    }

    private void play(CanonicalMatchEvaluation evaluation,
                      MutableTeamSeason[] rows,
                      int home, int away,
                      long seed) {
        CanonicalScoreSampler.GoalSample score = sampler.sample(evaluation, seed);
        rows[home].record(score.homeGoals(), score.awayGoals());
        rows[away].record(score.awayGoals(), score.homeGoals());
    }

    private static List<Integer> normalizeBuckets(List<Integer> values) {
        List<Integer> buckets = values == null || values.isEmpty()
                ? List.of(1, 4, 6, 10)
                : values.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (buckets.isEmpty() || buckets.stream().anyMatch(value -> value < 1 || value > CLUBS)) {
            throw new IllegalArgumentException("finish buckets must be within [1,20]");
        }
        return buckets;
    }

    private static List<TeamSummary> summarize(List<SeasonStanding> standings,
                                               int seasons,
                                               List<Integer> buckets) {
        List<TeamSummary> result = new ArrayList<>(CLUBS);
        for (int team = 0; team < CLUBS; team++) {
            final int teamIndex = team;
            List<SeasonStanding> rows = standings.stream()
                    .filter(row -> row.teamIndex() == teamIndex)
                    .sorted(Comparator.comparingInt(SeasonStanding::season))
                    .toList();
            double averagePoints = rows.stream().mapToInt(SeasonStanding::points).average().orElseThrow();
            double averagePosition = rows.stream().mapToInt(SeasonStanding::position).average().orElseThrow();
            double variance = rows.stream()
                    .mapToDouble(row -> Math.pow(row.points() - averagePoints, 2.0))
                    .average().orElse(0.0);
            List<Integer> orderedPoints = rows.stream().map(SeasonStanding::points).sorted().toList();
            double medianPoints = seasons % 2 == 0
                    ? (orderedPoints.get(seasons / 2 - 1) + orderedPoints.get(seasons / 2)) / 2.0
                    : orderedPoints.get(seasons / 2);
            Map<Integer, Double> topPercentages = new LinkedHashMap<>();
            for (Integer bucket : buckets) {
                long count = rows.stream().filter(row -> row.position() <= bucket).count();
                topPercentages.put(bucket, percentage(count, seasons));
            }
            long bottomThree = rows.stream().filter(row -> row.position() >= CLUBS - 2).count();
            result.add(new TeamSummary(team, teamKey(team), averagePoints, medianPoints,
                    Math.sqrt(variance), orderedPoints.get(0), orderedPoints.get(orderedPoints.size() - 1),
                    averagePosition, topPercentages, percentage(bottomThree, seasons),
                    rows.stream().mapToInt(SeasonStanding::goalsFor).average().orElse(0.0),
                    rows.stream().mapToInt(SeasonStanding::goalsAgainst).average().orElse(0.0),
                    rows.stream().mapToInt(SeasonStanding::wins).average().orElse(0.0),
                    rows.stream().mapToInt(SeasonStanding::draws).average().orElse(0.0),
                    rows.stream().mapToInt(SeasonStanding::losses).average().orElse(0.0)));
        }
        return List.copyOf(result);
    }

    private static double percentage(long count, int seasons) {
        return 100.0 * count / seasons;
    }

    private static String teamKey(int teamIndex) {
        return teamIndex == 0 ? "candidate-midtable" : "club-" + String.format("%02d", teamIndex + 1);
    }

    private static final class MutableTeamSeason {
        private final int teamIndex;
        private int wins;
        private int draws;
        private int losses;
        private int goalsFor;
        private int goalsAgainst;

        private MutableTeamSeason(int teamIndex) { this.teamIndex = teamIndex; }

        private void record(int scored, int conceded) {
            goalsFor += scored;
            goalsAgainst += conceded;
            if (scored > conceded) wins++;
            else if (scored == conceded) draws++;
            else losses++;
        }

        private int teamIndex() { return teamIndex; }
        private int goalsFor() { return goalsFor; }
        private int goalDifference() { return goalsFor - goalsAgainst; }
        private int points() { return wins * 3 + draws; }

        private SeasonStanding toStanding(int season, int position) {
            return new SeasonStanding(season, position, teamIndex, teamKey(teamIndex),
                    wins + draws + losses, wins, draws, losses,
                    goalsFor, goalsAgainst, goalDifference(), points());
        }
    }

    public record SeasonStanding(int season, int position, int teamIndex, String teamKey,
                                 int played, int wins, int draws, int losses,
                                 int goalsFor, int goalsAgainst, int goalDifference, int points) { }

    public record TeamSummary(int teamIndex, String teamKey,
                              double averagePoints, double medianPoints, double pointsStdDev,
                              int minimumPoints, int maximumPoints, double averagePosition,
                              Map<Integer, Double> topFinishPercentages,
                              double bottomThreePercentage,
                              double averageGoalsFor, double averageGoalsAgainst,
                              double averageWins, double averageDraws, double averageLosses) {
        public TeamSummary {
            topFinishPercentages = Map.copyOf(topFinishPercentages);
        }
    }

    public record Result(int seasons, int matches, long seed, List<Integer> finishBuckets,
                         List<SeasonStanding> standings, List<TeamSummary> teamSummaries) {
        public Result {
            finishBuckets = List.copyOf(finishBuckets);
            standings = List.copyOf(standings);
            teamSummaries = List.copyOf(teamSummaries);
        }

        public TeamSummary candidate() {
            return teamSummaries.stream().filter(summary -> summary.teamIndex() == 0)
                    .findFirst().orElseThrow();
        }
    }
}
