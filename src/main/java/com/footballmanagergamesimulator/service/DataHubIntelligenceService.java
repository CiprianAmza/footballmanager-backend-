package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Decision-oriented team analytics built only from persisted match events. */
@Service
public class DataHubIntelligenceService {

    private final MatchStatsRepository matchStatsRepository;
    private final CompetitionTeamInfoMatchRepository fixtureRepository;
    private final TeamRepository teamRepository;
    private final CompetitionRepository competitionRepository;

    public DataHubIntelligenceService(MatchStatsRepository matchStatsRepository,
                                      CompetitionTeamInfoMatchRepository fixtureRepository,
                                      TeamRepository teamRepository,
                                      CompetitionRepository competitionRepository) {
        this.matchStatsRepository = matchStatsRepository;
        this.fixtureRepository = fixtureRepository;
        this.teamRepository = teamRepository;
        this.competitionRepository = competitionRepository;
    }

    public DataHubIntelligence intelligence(long teamId, int season) {
        Map<Long, String> teamNames = teamRepository.findAll().stream()
                .collect(Collectors.toMap(Team::getId, Team::getName, (left, right) -> left));
        Map<Long, String> competitionNames = competitionRepository.findAll().stream()
                .collect(Collectors.toMap(Competition::getId, Competition::getName, (left, right) -> left));
        List<CompetitionTeamInfoMatch> fixtures = fixtureRepository
                .findAllBySeasonNumberAndTeamId(String.valueOf(season), teamId);
        Map<String, Integer> dayByMatch = fixtures.stream().collect(Collectors.toMap(
                row -> matchKey(row.getCompetitionId(), row.getRound(), row.getTeam1Id(), row.getTeam2Id()),
                CompetitionTeamInfoMatch::getDay, Math::min));

        List<MatchSlice> matches = new ArrayList<>();
        matchStatsRepository.findAllByTeam1IdAndSeasonNumber(teamId, season).forEach(row ->
                matches.add(slice(row, teamId, true, teamNames, competitionNames, dayByMatch)));
        matchStatsRepository.findAllByTeam2IdAndSeasonNumber(teamId, season).forEach(row ->
                matches.add(slice(row, teamId, false, teamNames, competitionNames, dayByMatch)));
        matches.sort(Comparator.comparingInt(MatchSlice::day).thenComparingInt(MatchSlice::round));

        Metrics seasonMetrics = metrics(matches);
        List<MatchSlice> recentRows = matches.subList(Math.max(0, matches.size() - 5), matches.size());
        Metrics recentMetrics = metrics(recentRows);
        List<StyleMetric> style = styleProfile(seasonMetrics);
        List<Insight> insights = insights(seasonMetrics, recentMetrics, matches.size());
        List<UpcomingFixture> upcoming = fixtures.stream()
                .filter(row -> row.getTeam1Score() < 0 && row.getTeam2Score() < 0)
                .filter(row -> row.getTeam1Id() > 0 && row.getTeam2Id() > 0)
                .sorted(Comparator.comparingInt(CompetitionTeamInfoMatch::getDay)
                        .thenComparingLong(CompetitionTeamInfoMatch::getRound))
                .limit(4)
                .map(row -> {
                    boolean home = row.getTeam1Id() == teamId;
                    long opponentId = home ? row.getTeam2Id() : row.getTeam1Id();
                    return new UpcomingFixture(row.getDay(), (int) row.getRound(), row.getCompetitionId(),
                            competitionNames.getOrDefault(row.getCompetitionId(), "Competition"), opponentId,
                            teamNames.getOrDefault(opponentId, "Unknown"), home ? "HOME" : "AWAY");
                }).toList();

        Metrics home = metrics(matches.stream().filter(MatchSlice::home).toList());
        Metrics away = metrics(matches.stream().filter(row -> !row.home()).toList());
        List<CompetitionSplit> competitionSplits = matches.stream()
                .collect(Collectors.groupingBy(MatchSlice::competitionId, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream().map(entry -> new CompetitionSplit(entry.getKey(),
                        competitionNames.getOrDefault(entry.getKey(), "Competition"), metrics(entry.getValue())))
                .sorted(Comparator.comparingInt((CompetitionSplit row) -> row.metrics().matches()).reversed())
                .toList();

        String confidence = matches.size() >= 12 ? "HIGH" : matches.size() >= 5 ? "MEDIUM" : "LOW";
        return new DataHubIntelligence(teamId, teamNames.getOrDefault(teamId, "Team"), season,
                confidence, "Match stats are observed; style scores and PPDA-like pressure are transparent derived proxies.",
                seasonMetrics, recentMetrics, home, away, style, insights,
                matches.stream().sorted(Comparator.comparingInt(MatchSlice::day).reversed()
                        .thenComparing(Comparator.comparingInt(MatchSlice::round).reversed())).limit(20).toList(),
                competitionSplits, upcoming);
    }

    private MatchSlice slice(MatchStats row, long teamId, boolean home, Map<Long, String> teamNames,
                             Map<Long, String> competitionNames, Map<String, Integer> dayByMatch) {
        long opponentId = home ? row.getTeam2Id() : row.getTeam1Id();
        int goals = home ? row.getHomeGoals() : row.getAwayGoals();
        int conceded = home ? row.getAwayGoals() : row.getHomeGoals();
        double xg = (home ? row.getHomeXg() : row.getAwayXg()) / 100.0;
        double xga = (home ? row.getAwayXg() : row.getHomeXg()) / 100.0;
        int possession = home ? row.getHomePossession() : row.getAwayPossession();
        int shots = home ? row.getHomeShots() : row.getAwayShots();
        int shotsAgainst = home ? row.getAwayShots() : row.getHomeShots();
        int onTarget = home ? row.getHomeShotsOnTarget() : row.getAwayShotsOnTarget();
        int passAccuracy = home ? row.getHomePassAccuracy() : row.getAwayPassAccuracy();
        int opponentPasses = home ? row.getAwayPasses() : row.getHomePasses();
        int defensiveActions = (home ? row.getHomeTackles() : row.getAwayTackles())
                + (home ? row.getHomeInterceptions() : row.getAwayInterceptions())
                + (home ? row.getHomeFouls() : row.getAwayFouls());
        String result = goals > conceded ? "W" : goals == conceded ? "D" : "L";
        double control = clamp(50 + (xg - xga) * 14 + (possession - 50) * .45, 0, 100);
        int day = dayByMatch.getOrDefault(matchKey(row.getCompetitionId(), row.getRoundNumber(), row.getTeam1Id(), row.getTeam2Id()), row.getRoundNumber());
        return new MatchSlice(row.getId(), day, row.getRoundNumber(), row.getCompetitionId(),
                competitionNames.getOrDefault(row.getCompetitionId(), "Competition"), opponentId,
                teamNames.getOrDefault(opponentId, "Unknown"), home, result, goals, conceded,
                round(xg), round(xga), possession, shots, shotsAgainst, onTarget, passAccuracy,
                opponentPasses, defensiveActions, round(control));
    }

    private Metrics metrics(List<MatchSlice> rows) {
        int count = rows.size();
        if (count == 0) return new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        int goals = rows.stream().mapToInt(MatchSlice::goals).sum();
        int conceded = rows.stream().mapToInt(MatchSlice::conceded).sum();
        double xg = rows.stream().mapToDouble(MatchSlice::xg).sum();
        double xga = rows.stream().mapToDouble(MatchSlice::xga).sum();
        int shots = rows.stream().mapToInt(MatchSlice::shots).sum();
        int shotsAgainst = rows.stream().mapToInt(MatchSlice::shotsAgainst).sum();
        int onTarget = rows.stream().mapToInt(MatchSlice::shotsOnTarget).sum();
        int opponentPasses = rows.stream().mapToInt(MatchSlice::opponentPasses).sum();
        int defensiveActions = rows.stream().mapToInt(MatchSlice::defensiveActions).sum();
        return new Metrics(count, goals, conceded, round(goals * 1.0 / count), round(conceded * 1.0 / count),
                round(xg / count), round(xga / count), round((xg - xga) / count),
                round(rows.stream().mapToInt(MatchSlice::possession).average().orElse(0)),
                round(shots * 1.0 / count), round(shotsAgainst * 1.0 / count),
                round(rows.stream().mapToInt(MatchSlice::passAccuracy).average().orElse(0)),
                round(shots == 0 ? 0 : xg / shots), round(shots == 0 ? 0 : goals * 100.0 / shots),
                round(shots == 0 ? 0 : onTarget * 100.0 / shots),
                round(defensiveActions == 0 ? 0 : opponentPasses * 1.0 / defensiveActions));
    }

    private List<StyleMetric> styleProfile(Metrics m) {
        return List.of(
                style("Possession control", m.possession(), score(m.possession(), 35, 65), m.possession() + "%", "OBSERVED"),
                style("Chance creation", m.xgForPerMatch(), score(m.xgForPerMatch(), .5, 2.5), m.xgForPerMatch() + " xG/m", "OBSERVED"),
                style("Shot volume", m.shotsPerMatch(), score(m.shotsPerMatch(), 5, 20), m.shotsPerMatch() + "/m", "OBSERVED"),
                style("Chance quality", m.xgPerShot(), score(m.xgPerShot(), .05, .20), m.xgPerShot() + " xG/shot", "DERIVED"),
                style("Press intensity", m.pressureProxy(), 100 - score(m.pressureProxy(), 5, 18), m.pressureProxy() + " PPDA-like", "DERIVED_PROXY"),
                style("Defensive resistance", m.xgAgainstPerMatch(), 100 - score(m.xgAgainstPerMatch(), .5, 2.5), m.xgAgainstPerMatch() + " xGA/m", "OBSERVED"));
    }

    private StyleMetric style(String label, double raw, double score, String display, String basis) {
        return new StyleMetric(label, round(raw), round(clamp(score, 0, 100)), display, basis);
    }

    private List<Insight> insights(Metrics season, Metrics recent, int sample) {
        List<Insight> result = new ArrayList<>();
        if (sample == 0) return List.of(new Insight("INFO", "Awaiting match evidence", "No matches have been recorded for this season yet.", "Review after the first competitive fixture.", "LOW"));
        if (season.xgDifferencePerMatch() >= .35) result.add(new Insight("POSITIVE", "Underlying performance is strong", "The team creates " + season.xgDifferencePerMatch() + " more xG than it allows per match.", "Protect the chance-quality advantage before changing the system.", "HIGH"));
        else if (season.xgDifferencePerMatch() <= -.35) result.add(new Insight("RISK", "Opponents own the better chances", "The xG difference is " + season.xgDifferencePerMatch() + " per match.", "Reduce the space conceded and review shot locations against.", "HIGH"));
        double finishingDelta = season.goalsPerMatch() - season.xgForPerMatch();
        if (finishingDelta >= .35) result.add(new Insight("WATCH", "Results lean on hot finishing", "Goals exceed xG by " + round(finishingDelta) + " per match.", "Expect variance; improve repeatable chance volume.", "MEDIUM"));
        if (finishingDelta <= -.35) result.add(new Insight("OPPORTUNITY", "Finishing trails chance quality", "Goals are " + round(Math.abs(finishingDelta)) + " below xG per match.", "Keep generating chances and review shot execution.", "MEDIUM"));
        if (season.possession() >= 55 && season.xgForPerMatch() < 1.15) result.add(new Insight("RISK", "Possession lacks penetration", "High possession is producing only " + season.xgForPerMatch() + " xG per match.", "Add runners, tempo or more progressive passing.", "MEDIUM"));
        if (season.pressureProxy() > 14) result.add(new Insight("WATCH", "Pressure arrives late", "The whole-pitch PPDA-like proxy is " + season.pressureProxy() + ".", "Review pressing line and counter-press responsibilities.", "LOW"));
        if (recent.matches() >= 3 && recent.xgDifferencePerMatch() < season.xgDifferencePerMatch() - .3) result.add(new Insight("TREND", "Recent process is declining", "Last-five xG difference is " + recent.xgDifferencePerMatch() + " versus " + season.xgDifferencePerMatch() + " for the season.", "Compare recent opposition and player availability before reacting.", "MEDIUM"));
        if (result.isEmpty()) result.add(new Insight("INFO", "Performance is near its baseline", "No major statistical deviation is currently visible.", "Use opponent-specific analysis for the next adjustment.", sample >= 8 ? "MEDIUM" : "LOW"));
        return result;
    }

    private double score(double value, double minimum, double maximum) {
        return (value - minimum) * 100.0 / Math.max(maximum - minimum, .0001);
    }

    private String matchKey(long competition, long round, long home, long away) {
        return competition + ":" + round + ":" + home + ":" + away;
    }

    private double clamp(double value, double minimum, double maximum) { return Math.max(minimum, Math.min(maximum, value)); }
    private double round(double value) { return Math.round(value * 100.0) / 100.0; }

    public record Metrics(int matches, int goals, int conceded, double goalsPerMatch, double concededPerMatch,
                          double xgForPerMatch, double xgAgainstPerMatch, double xgDifferencePerMatch,
                          double possession, double shotsPerMatch, double shotsAgainstPerMatch,
                          double passAccuracy, double xgPerShot, double conversionPercentage,
                          double shotAccuracyPercentage, double pressureProxy) {}
    public record StyleMetric(String label, double rawValue, double score, String displayValue, String basis) {}
    public record Insight(String type, String title, String evidence, String action, String confidence) {}
    public record MatchSlice(long matchId, int day, int round, long competitionId, String competitionName,
                             long opponentId, String opponentName, boolean home, String result, int goals, int conceded,
                             double xg, double xga, int possession, int shots, int shotsAgainst, int shotsOnTarget,
                             int passAccuracy, int opponentPasses, int defensiveActions, double controlScore) {}
    public record CompetitionSplit(long competitionId, String competitionName, Metrics metrics) {}
    public record UpcomingFixture(int day, int round, long competitionId, String competitionName,
                                  long opponentId, String opponentName, String venue) {}
    public record DataHubIntelligence(long teamId, String teamName, int season, String confidence,
                                      String methodologyNote, Metrics seasonMetrics, Metrics recentMetrics,
                                      Metrics homeMetrics, Metrics awayMetrics, List<StyleMetric> styleProfile,
                                      List<Insight> insights, List<MatchSlice> matches,
                                      List<CompetitionSplit> competitionSplits, List<UpcomingFixture> upcomingFixtures) {}
}
