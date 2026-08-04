package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class UnderlyingPerformanceService {

    private static final int MAX_POISSON_GOALS = 10;
    private final MatchStatsRepository matchStatsRepository;

    public UnderlyingPerformanceService(MatchStatsRepository matchStatsRepository) {
        this.matchStatsRepository = matchStatsRepository;
    }

    public UnderlyingPerformance performance(long teamId, int seasonNumber) {
        List<TeamMatch> matches = new ArrayList<>();
        matchStatsRepository.findAllByTeam1IdAndSeasonNumber(teamId, seasonNumber)
                .forEach(match -> matches.add(fromHomeTeam(match)));
        matchStatsRepository.findAllByTeam2IdAndSeasonNumber(teamId, seasonNumber)
                .forEach(match -> matches.add(fromAwayTeam(match)));
        matches.sort(Comparator.comparingLong(TeamMatch::matchId));

        int actualPoints = matches.stream().mapToInt(TeamMatch::actualPoints).sum();
        int goals = matches.stream().mapToInt(TeamMatch::goals).sum();
        int goalsConceded = matches.stream().mapToInt(TeamMatch::goalsConceded).sum();
        int shots = matches.stream().mapToInt(TeamMatch::shots).sum();
        double expectedPoints = matches.stream().mapToDouble(TeamMatch::expectedPoints).sum();
        double xg = matches.stream().mapToDouble(TeamMatch::xg).sum();
        double xga = matches.stream().mapToDouble(TeamMatch::xga).sum();
        int matchCount = matches.size();

        double actualConversion = shots == 0 ? 0 : goals * 100.0 / shots;
        double expectedConversion = shots == 0 ? 0 : xg * 100.0 / shots;
        double xgPer90 = matchCount == 0 ? 0 : xg / matchCount;
        double xgaPer90 = matchCount == 0 ? 0 : xga / matchCount;

        List<MatchPerformance> recentMatches = matches.stream()
                .skip(Math.max(0, matches.size() - 10L))
                .map(match -> new MatchPerformance(
                        match.matchId(), match.round(), match.home(), match.goals(), match.goalsConceded(),
                        round(match.xg()), round(match.xga()), match.actualPoints(), round(match.expectedPoints())))
                .toList();

        return new UnderlyingPerformance(
                teamId,
                seasonNumber,
                matchCount,
                actualPoints,
                round(expectedPoints),
                round(actualPoints - expectedPoints),
                goals,
                round(xg),
                round(goals - xg),
                goalsConceded,
                round(xga),
                round(xga - goalsConceded),
                round(actualConversion),
                round(expectedConversion),
                round(actualConversion - expectedConversion),
                round(xgPer90),
                round(xgaPer90),
                round(xgPer90 - xgaPer90),
                confidence(matchCount),
                "Expected Points uses independent Poisson goal probabilities derived from each match's xG and xGA.",
                recentMatches);
    }

    private TeamMatch fromHomeTeam(MatchStats match) {
        return teamMatch(match, true, match.getHomeGoals(), match.getAwayGoals(), match.getHomeShots(),
                match.getHomeXg() / 100.0, match.getAwayXg() / 100.0);
    }

    private TeamMatch fromAwayTeam(MatchStats match) {
        return teamMatch(match, false, match.getAwayGoals(), match.getHomeGoals(), match.getAwayShots(),
                match.getAwayXg() / 100.0, match.getHomeXg() / 100.0);
    }

    private TeamMatch teamMatch(MatchStats match, boolean home, int goals, int goalsConceded, int shots,
                                double xg, double xga) {
        int actualPoints = goals > goalsConceded ? 3 : goals == goalsConceded ? 1 : 0;
        return new TeamMatch(match.getId(), match.getRoundNumber(), home, goals, goalsConceded, shots,
                xg, xga, actualPoints, expectedPoints(xg, xga));
    }

    static double expectedPoints(double xg, double xga) {
        double[] scoring = poissonProbabilities(Math.max(0, xg));
        double[] conceding = poissonProbabilities(Math.max(0, xga));
        double winProbability = 0;
        double drawProbability = 0;

        for (int goalsFor = 0; goalsFor <= MAX_POISSON_GOALS; goalsFor++) {
            for (int goalsAgainst = 0; goalsAgainst <= MAX_POISSON_GOALS; goalsAgainst++) {
                double probability = scoring[goalsFor] * conceding[goalsAgainst];
                if (goalsFor > goalsAgainst) {
                    winProbability += probability;
                } else if (goalsFor == goalsAgainst) {
                    drawProbability += probability;
                }
            }
        }
        return 3 * winProbability + drawProbability;
    }

    private static double[] poissonProbabilities(double expectedGoals) {
        double[] probabilities = new double[MAX_POISSON_GOALS + 1];
        probabilities[0] = Math.exp(-expectedGoals);
        double accountedFor = probabilities[0];
        for (int goals = 1; goals < MAX_POISSON_GOALS; goals++) {
            probabilities[goals] = probabilities[goals - 1] * expectedGoals / goals;
            accountedFor += probabilities[goals];
        }
        probabilities[MAX_POISSON_GOALS] = Math.max(0, 1 - accountedFor);
        return probabilities;
    }

    private static String confidence(int matches) {
        if (matches >= 12) return "HIGH";
        if (matches >= 5) return "MEDIUM";
        return "LOW";
    }

    private static double round(double value) {
        if (Math.abs(value) < 0.005) return 0;
        return Math.round(value * 100.0) / 100.0;
    }

    private record TeamMatch(long matchId, int round, boolean home, int goals, int goalsConceded, int shots,
                             double xg, double xga, int actualPoints, double expectedPoints) {
    }

    public record MatchPerformance(long matchId, int round, boolean home, int goals, int goalsConceded,
                                   double xg, double xga, int actualPoints, double expectedPoints) {
    }

    public record UnderlyingPerformance(long teamId, int seasonNumber, int matches,
                                        int actualPoints, double expectedPoints, double pointsDelta,
                                        int goals, double xg, double finishingDelta,
                                        int goalsConceded, double xga, double goalsPrevented,
                                        double actualConversionPercentage, double expectedConversionPercentage,
                                        double conversionDeltaPercentagePoints,
                                        double xgPer90, double xgaPer90, double xgDifferencePer90,
                                        String confidence, String methodology,
                                        List<MatchPerformance> recentMatches) {
    }
}
