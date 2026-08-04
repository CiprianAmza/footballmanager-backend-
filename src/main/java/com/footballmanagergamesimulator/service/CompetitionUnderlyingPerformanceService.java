package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class CompetitionUnderlyingPerformanceService {

    static final double BALANCED_XG_THRESHOLD = 0.25;

    private final MatchStatsRepository matchStatsRepository;
    private final CompetitionTeamInfoRepository competitionTeamInfoRepository;
    private final TeamRepository teamRepository;

    public CompetitionUnderlyingPerformanceService(MatchStatsRepository matchStatsRepository,
                                                   CompetitionTeamInfoRepository competitionTeamInfoRepository,
                                                   TeamRepository teamRepository) {
        this.matchStatsRepository = matchStatsRepository;
        this.competitionTeamInfoRepository = competitionTeamInfoRepository;
        this.teamRepository = teamRepository;
    }

    public CompetitionUnderlyingPerformance performance(long competitionId, int seasonNumber) {
        List<MatchStats> matchRows = matchStatsRepository
                .findAllByCompetitionIdAndSeasonNumber(competitionId, seasonNumber).stream()
                .sorted(Comparator.comparingInt(MatchStats::getRoundNumber).thenComparingLong(MatchStats::getId))
                .toList();

        Set<Long> teamIds = new LinkedHashSet<>();
        competitionTeamInfoRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, seasonNumber)
                .stream().map(CompetitionTeamInfo::getTeamId).filter(id -> id > 0).forEach(teamIds::add);
        matchRows.forEach(row -> {
            teamIds.add(row.getTeam1Id());
            teamIds.add(row.getTeam2Id());
        });

        Map<Long, String> names = teamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(Team::getId, Team::getName, (left, right) -> left));
        Map<Long, List<TeamMatch>> matchesByTeam = new LinkedHashMap<>();
        teamIds.forEach(id -> matchesByTeam.put(id, new ArrayList<>()));
        for (MatchStats row : matchRows) {
            matchesByTeam.computeIfAbsent(row.getTeam1Id(), ignored -> new ArrayList<>()).add(slice(row, true));
            matchesByTeam.computeIfAbsent(row.getTeam2Id(), ignored -> new ArrayList<>()).add(slice(row, false));
        }

        List<Standing> standings = matchesByTeam.entrySet().stream()
                .map(entry -> standing(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(Standing::points).reversed()
                        .thenComparing(Comparator.comparingInt(Standing::goalDifference).reversed())
                        .thenComparing(Comparator.comparingInt(Standing::goalsFor).reversed())
                        .thenComparingLong(Standing::teamId))
                .toList();
        Map<Long, Integer> rankByTeam = new LinkedHashMap<>();
        for (int index = 0; index < standings.size(); index++) {
            rankByTeam.put(standings.get(index).teamId(), index + 1);
        }

        int topHalfLimit = Math.max(1, (standings.size() + 1) / 2);
        List<TeamUnderlyingRow> teams = standings.stream().map(standing -> {
            long teamId = standing.teamId();
            List<TeamMatch> matches = matchesByTeam.getOrDefault(teamId, List.of());
            return teamRow(rankByTeam.get(teamId), teamId, names.getOrDefault(teamId, "Team " + teamId),
                    matches, rankByTeam, topHalfLimit);
        }).toList();

        return new CompetitionUnderlyingPerformance(
                competitionId,
                seasonNumber,
                matchRows.size(),
                teams.size(),
                BALANCED_XG_THRESHOLD,
                "xPts uses independent Poisson probabilities from match xG/xGA. Rolling xGD is per 90. "
                        + "Top half includes Top 4; balanced matches have |xG-xGA| <= 0.25. "
                        + "Luck index is actual points per match minus expected points per match.",
                teams);
    }

    private TeamUnderlyingRow teamRow(int rank, long teamId, String teamName, List<TeamMatch> matches,
                                      Map<Long, Integer> rankByTeam, int topHalfLimit) {
        Aggregate season = aggregate(matches);
        long higherXg = matches.stream().filter(match -> match.xg() > match.xga()).count();
        double higherXgPercentage = matches.isEmpty() ? 0 : higherXg * 100.0 / matches.size();
        Predicate<TeamMatch> top4 = match -> rankByTeam.getOrDefault(match.opponentId(), Integer.MAX_VALUE) <= 4;
        Predicate<TeamMatch> topHalf = match -> rankByTeam.getOrDefault(match.opponentId(), Integer.MAX_VALUE) <= topHalfLimit;
        Predicate<TeamMatch> lowerHalf = match -> rankByTeam.getOrDefault(match.opponentId(), 0) > topHalfLimit;

        double luckPoints = season.actualPoints() - season.expectedPoints();
        double luckIndex = matches.isEmpty() ? 0 : luckPoints / matches.size();
        return new TeamUnderlyingRow(
                rank,
                teamId,
                teamName,
                matches.size(),
                round(season.xgDifferencePer90()),
                rolling(matches, 5),
                rolling(matches, 10),
                rolling(matches, 20),
                round(higherXgPercentage),
                season.actualPoints(),
                round(season.expectedPoints()),
                round(luckPoints),
                round(luckIndex),
                segment(matches.stream().filter(match -> Math.abs(match.xg() - match.xga()) <= BALANCED_XG_THRESHOLD).toList()),
                segment(matches.stream().filter(top4).toList()),
                segment(matches.stream().filter(topHalf).toList()),
                segment(matches.stream().filter(lowerHalf).toList()));
    }

    private WindowPerformance rolling(List<TeamMatch> matches, int requestedMatches) {
        List<TeamMatch> window = matches.subList(Math.max(0, matches.size() - requestedMatches), matches.size());
        return new WindowPerformance(requestedMatches, window.size(), round(aggregate(window).xgDifferencePer90()));
    }

    private SegmentPerformance segment(List<TeamMatch> matches) {
        Aggregate aggregate = aggregate(matches);
        int wins = (int) matches.stream().filter(match -> match.actualPoints() == 3).count();
        int draws = (int) matches.stream().filter(match -> match.actualPoints() == 1).count();
        int losses = matches.size() - wins - draws;
        return new SegmentPerformance(matches.size(), wins, draws, losses, aggregate.actualPoints(),
                round(aggregate.expectedPoints()), round(aggregate.xgDifferencePer90()));
    }

    private Aggregate aggregate(List<TeamMatch> matches) {
        if (matches.isEmpty()) return new Aggregate(0, 0, 0);
        int actualPoints = matches.stream().mapToInt(TeamMatch::actualPoints).sum();
        double expectedPoints = matches.stream().mapToDouble(TeamMatch::expectedPoints).sum();
        double xgDifference = matches.stream().mapToDouble(match -> match.xg() - match.xga()).sum();
        return new Aggregate(actualPoints, expectedPoints, xgDifference / matches.size());
    }

    private Standing standing(long teamId, List<TeamMatch> matches) {
        int points = matches.stream().mapToInt(TeamMatch::actualPoints).sum();
        int goalsFor = matches.stream().mapToInt(TeamMatch::goals).sum();
        int goalsAgainst = matches.stream().mapToInt(TeamMatch::goalsConceded).sum();
        return new Standing(teamId, points, goalsFor - goalsAgainst, goalsFor);
    }

    private TeamMatch slice(MatchStats row, boolean home) {
        long opponentId = home ? row.getTeam2Id() : row.getTeam1Id();
        int goals = home ? row.getHomeGoals() : row.getAwayGoals();
        int conceded = home ? row.getAwayGoals() : row.getHomeGoals();
        double xg = (home ? row.getHomeXg() : row.getAwayXg()) / 100.0;
        double xga = (home ? row.getAwayXg() : row.getHomeXg()) / 100.0;
        int actualPoints = goals > conceded ? 3 : goals == conceded ? 1 : 0;
        return new TeamMatch(row.getId(), row.getRoundNumber(), opponentId, goals, conceded, xg, xga,
                actualPoints, UnderlyingPerformanceService.expectedPoints(xg, xga));
    }

    private static double round(double value) {
        if (Math.abs(value) < 0.005) return 0;
        return Math.round(value * 100.0) / 100.0;
    }

    private record TeamMatch(long matchId, int round, long opponentId, int goals, int goalsConceded,
                             double xg, double xga, int actualPoints, double expectedPoints) {
    }
    private record Standing(long teamId, int points, int goalDifference, int goalsFor) {
    }
    private record Aggregate(int actualPoints, double expectedPoints, double xgDifferencePer90) {
    }

    public record WindowPerformance(int requestedMatches, int sampleMatches, double xgDifferencePer90) {
    }
    public record SegmentPerformance(int matches, int wins, int draws, int losses, int points,
                                     double expectedPoints, double xgDifferencePer90) {
    }
    public record TeamUnderlyingRow(int rank, long teamId, String teamName, int matches,
                                    double xgDifferencePer90,
                                    WindowPerformance rolling5, WindowPerformance rolling10, WindowPerformance rolling20,
                                    double higherXgMatchPercentage,
                                    int actualPoints, double expectedPoints, double luckPoints, double luckIndex,
                                    SegmentPerformance balancedMatches,
                                    SegmentPerformance versusTop4,
                                    SegmentPerformance versusUpperHalf,
                                    SegmentPerformance versusLowerHalf) {
    }
    public record CompetitionUnderlyingPerformance(long competitionId, int seasonNumber, int matches,
                                                   int teams, double balancedXgThreshold, String methodology,
                                                   List<TeamUnderlyingRow> teamPerformance) {
    }
}
