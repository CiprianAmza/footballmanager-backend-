package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.AwardWeightingConfig;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.CompetitionStatLine;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.PlayerSeasonStat;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.model.TeamDataHubStats;
import com.footballmanagergamesimulator.model.Transfer;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.PlayerSeasonStatRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TransferRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Stats aggregation and leaderboard computations extracted from
 * {@link com.footballmanagergamesimulator.controller.StatsController}. The
 * controller keeps the REST mappings and delegates to this service.
 *
 * <p>Each public method aggregates raw scorer/match-stats/match-event rows
 * into a frontend-ready shape (TeamDataHub overview, championship leaderboards,
 * competition top-N rankings, player form/comparison profiles, all-time
 * champions table).
 */
@Service
public class StatsAggregationService {

    @Autowired private ScorerRepository scorerRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private CompetitionHistoryRepository competitionHistoryRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private MatchEventRepository matchEventRepository;
    @Autowired private MatchStatsRepository matchStatsRepository;
    @Autowired private TransferRepository transferRepository;
    @Autowired private TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired private PlayerSeasonStatRepository playerSeasonStatRepository;
    @Autowired private LeagueStrengthService leagueStrengthService;
    @Autowired private AwardWeightingConfig awardWeightingConfig;
    @Autowired private CompetitionProgressService competitionProgressService;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    @Autowired private RoundRepository roundRepository;

    private static final Pattern RESULT_SCORE_PATTERN = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)");

    // ==================== GLOBAL SEASON OVERVIEW ====================

    /** Global current-season player rankings for the Overview screen. */
    public Map<String, Object> getSeasonOverviewStats(int seasonNumber, int requestedLimit) {
        return getSeasonOverviewStats(seasonNumber, requestedLimit, "LEAGUE");
    }

    /**
     * Builds every leaderboard from the same competition scope. This is important:
     * showing league goals next to cup assists made a perfectly valid dataset look
     * contradictory in the UI.
     */
    public Map<String, Object> getSeasonOverviewStats(int seasonNumber, int requestedLimit,
                                                       String requestedScope) {
        int limit = Math.max(1, Math.min(50, requestedLimit));
        OverviewStatsScope scope = OverviewStatsScope.from(requestedScope);
        LeagueStrengthService.LeagueStrengthTable leagueStrength = leagueStrengthService.calculate(seasonNumber);
        List<Scorer> scorers = scorerRepository.findAllBySeasonNumber(seasonNumber).stream()
                .filter(s -> s.getTeamScore() >= 0 && s.getOpponentTeamId() >= 0)
                .filter(s -> scope.includes(s.getCompetitionTypeId()))
                .toList();
        Map<Long, Integer> competitionTypes = competitionRepository.findAll().stream()
                .collect(Collectors.toMap(Competition::getId, competition -> (int) competition.getTypeId(),
                        (left, ignored) -> left));
        List<PlayerSeasonStat> seasonStats = playerSeasonStatRepository.findAllBySeasonNumber(seasonNumber).stream()
                .filter(stat -> scope.includes(competitionTypes.getOrDefault(stat.getCompetitionId(), 0)))
                .toList();

        Set<Long> playerIds = new LinkedHashSet<>();
        Set<Long> teamIds = new LinkedHashSet<>();
        scorers.forEach(s -> {
            playerIds.add(s.getPlayerId());
            if (s.getTeamId() > 0) teamIds.add(s.getTeamId());
        });
        seasonStats.forEach(s -> {
            playerIds.add(s.getPlayerId());
            if (s.getTeamId() > 0) teamIds.add(s.getTeamId());
        });

        Map<Long, Human> humans = humanRepository.findAllById(playerIds).stream()
                .collect(Collectors.toMap(Human::getId, h -> h));
        Map<Long, String> teamNames = teamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));

        Map<Long, GoldenBootAccumulator> bootByPlayer = new HashMap<>();
        Map<Long, ScorerAccumulator> scorerByPlayer = new HashMap<>();
        for (Scorer scorer : scorers) {
            ScorerAccumulator all = scorerByPlayer.computeIfAbsent(
                    scorer.getPlayerId(), ignored -> new ScorerAccumulator());
            all.add(scorer);

            double leagueMultiplier = scope == OverviewStatsScope.LEAGUE
                    ? leagueStrength.competitionMultiplier(scorer.getCompetitionId())
                    : 1.0;
            bootByPlayer.computeIfAbsent(scorer.getPlayerId(), ignored -> new GoldenBootAccumulator())
                    .add(scorer, leagueMultiplier,
                            awardWeightingConfig.getGoldenBoot().getGoalWeight(),
                            awardWeightingConfig.getGoldenBoot().getAssistWeight());
        }

        List<Map<String, Object>> goldenBoot = bootByPlayer.entrySet().stream()
                .filter(entry -> entry.getValue().goals > 0)
                .map(entry -> scoringRow(entry.getKey(), entry.getValue(), humans, teamNames, scope))
                .sorted(leaderComparator(scope == OverviewStatsScope.LEAGUE ? "awardPoints" : "goals", "goals"))
                .limit(limit)
                .collect(Collectors.toCollection(ArrayList::new));
        addRanks(goldenBoot);

        Map<Long, SeasonStatAccumulator> statsByPlayer = new HashMap<>();
        for (PlayerSeasonStat stat : seasonStats) {
            statsByPlayer.computeIfAbsent(stat.getPlayerId(), ignored -> new SeasonStatAccumulator()).add(stat);
        }

        List<Map<String, Object>> categories = new ArrayList<>();
        categories.add(scorerCategory("assists", "Most assists", "Assists", scorerByPlayer,
                humans, teamNames, limit, false));
        categories.add(scorerCategory("averageRating", "Highest average rating", "Rating", scorerByPlayer,
                humans, teamNames, limit, true));
        categories.add(seasonStatCategory("passesCompleted", "Most completed passes", "Passes", statsByPlayer,
                humans, teamNames, limit, accumulator -> accumulator.passesCompleted));
        categories.add(seasonStatCategory("chancesCreated", "Most chances created", "Chances", statsByPlayer,
                humans, teamNames, limit, accumulator -> accumulator.chancesCreated));
        categories.add(seasonStatCategory("shots", "Most shots", "Shots", statsByPlayer,
                humans, teamNames, limit, accumulator -> accumulator.shots));
        categories.add(seasonStatCategory("tackles", "Most tackles", "Tackles", statsByPlayer,
                humans, teamNames, limit, accumulator -> accumulator.tackles));
        categories.add(seasonStatCategory("pressures", "Most pressures", "Pressures", statsByPlayer,
                humans, teamNames, limit, accumulator -> accumulator.pressures));
        categories.add(seasonStatCategory("defensiveActions", "Most defensive actions", "Actions", statsByPlayer,
                humans, teamNames, limit, accumulator -> accumulator.defensiveActions));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("season", seasonNumber);
        result.put("scope", scope.name());
        result.put("scopeLabel", scope.label);
        result.put("scoringTitle", scope == OverviewStatsScope.LEAGUE ? "Golden Boot" : "Top scorers");
        result.put("goldenBoot", goldenBoot);
        result.put("goldenBootRule", scope == OverviewStatsScope.LEAGUE
                ? goldenBootRule(leagueStrength)
                : "Goals scored in " + scope.label.toLowerCase() + "; no league weighting is applied");
        result.put("goldenBootGoalWeight", awardWeightingConfig.getGoldenBoot().getGoalWeight());
        result.put("goldenBootAssistWeight", awardWeightingConfig.getGoldenBoot().getAssistWeight());
        result.put("leagueStrength", leagueStrength);
        result.put("categoriesScope", scope.label);
        result.put("categories", categories);
        return result;
    }

    /**
     * Returns the selected club's most productive players for one season. Unlike
     * the legacy all-player leaderboard, this is built from played match rows, so
     * assists and average match ratings are real season statistics and placeholder
     * rows created before kick-off never count as appearances.
     */
    public List<Map<String, Object>> getTeamSeasonPlayerStats(long teamId, int seasonNumber,
                                                               int requestedLimit) {
        int limit = Math.max(1, Math.min(20, requestedLimit));
        Map<Long, ScorerAccumulator> byPlayer = new HashMap<>();

        scorerRepository.findAllByTeamIdAndSeasonNumber(teamId, seasonNumber).stream()
                .filter(scorer -> scorer.getTeamScore() >= 0 && scorer.getOpponentTeamId() >= 0)
                .forEach(scorer -> byPlayer
                        .computeIfAbsent(scorer.getPlayerId(), ignored -> new ScorerAccumulator())
                        .add(scorer));

        if (byPlayer.isEmpty()) return List.of();

        Map<Long, Human> players = humanRepository.findAllById(byPlayer.keySet()).stream()
                .collect(Collectors.toMap(Human::getId, human -> human));
        List<Map<String, Object>> rows = byPlayer.entrySet().stream()
                .map(entry -> {
                    Human player = players.get(entry.getKey());
                    ScorerAccumulator stats = entry.getValue();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("playerId", entry.getKey());
                    row.put("playerName", player == null ? "Unknown" : player.getName());
                    row.put("position", player == null ? "" : player.getPosition());
                    row.put("teamId", teamId);
                    row.put("appearances", stats.appearances);
                    row.put("goals", stats.goals);
                    row.put("assists", stats.assists);
                    row.put("goalContributions", stats.goals + stats.assists);
                    row.put("averageRating", round(stats.ratingTotal / stats.appearances, 2));
                    return row;
                })
                .sorted(leaderComparator("goalContributions", "averageRating"))
                .limit(limit)
                .collect(Collectors.toCollection(ArrayList::new));
        addRanks(rows);
        return rows;
    }

    private Map<String, Object> scoringRow(long playerId, GoldenBootAccumulator value,
                                           Map<Long, Human> humans, Map<Long, String> teamNames,
                                           OverviewStatsScope scope) {
        Map<String, Object> row = identityRow(playerId, value.teamId, value.teamName, humans, teamNames);
        row.put("appearances", value.appearances);
        row.put("goals", value.goals);
        row.put("assists", value.assists);
        row.put("firstLeagueGoals", value.firstLeagueGoals);
        row.put("secondLeagueGoals", value.secondLeagueGoals);
        row.put("weightedGoalPoints", scope == OverviewStatsScope.LEAGUE
                ? round(value.weightedGoalPoints, 1) : (double) value.goals);
        row.put("weightedAssistPoints", scope == OverviewStatsScope.LEAGUE
                ? round(value.weightedAssistPoints, 1) : 0.0);
        row.put("awardPoints", scope == OverviewStatsScope.LEAGUE
                ? round(value.awardPoints, 1) : (double) value.goals);
        // Backward-compatible field for older frontends; now represents the full
        // Golden Boot score (goals + assists), not the retired L1/L2 goal formula.
        row.put("weightedGoals", scope == OverviewStatsScope.LEAGUE
                ? round(value.awardPoints, 1) : (double) value.goals);
        return row;
    }

    private String goldenBootRule(LeagueStrengthService.LeagueStrengthTable table) {
        List<String> bands = new ArrayList<>();
        int minimum = 1;
        for (LeagueStrengthService.RankMultiplierTier tier : table.tiers()) {
            bands.add("ranks " + minimum + "-" + tier.maximumRank() + " ×" + formatWeight(tier.multiplier()));
            minimum = tier.maximumRank() + 1;
        }
        bands.add("remaining leagues ×" + formatWeight(table.defaultMultiplier()));
        return "Points = league multiplier × (goals × "
                + formatWeight(awardWeightingConfig.getGoldenBoot().getGoalWeight())
                + " + assists × "
                + formatWeight(awardWeightingConfig.getGoldenBoot().getAssistWeight())
                + "). League strength: " + String.join(", ", bands) + ".";
    }

    private String formatWeight(double value) {
        return value == Math.rint(value)
                ? String.valueOf((int) value)
                : String.valueOf(value);
    }

    private enum OverviewStatsScope {
        LEAGUE("Domestic leagues"),
        CUP("Domestic cups"),
        EUROPEAN("European competitions"),
        ALL("All competitions");

        private final String label;

        OverviewStatsScope(String label) {
            this.label = label;
        }

        static OverviewStatsScope from(String value) {
            if (value == null || value.isBlank()) return LEAGUE;
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return LEAGUE;
            }
        }

        boolean includes(int competitionTypeId) {
            return switch (this) {
                case LEAGUE -> competitionTypeId == 1 || competitionTypeId == 3;
                case CUP -> competitionTypeId == 2 || competitionTypeId == 6;
                case EUROPEAN -> competitionTypeId == 4 || competitionTypeId == 5;
                case ALL -> competitionTypeId > 0;
            };
        }
    }

    private Map<String, Object> scorerCategory(String key, String title, String unit,
                                                Map<Long, ScorerAccumulator> values,
                                                Map<Long, Human> humans, Map<Long, String> teamNames,
                                                int limit, boolean averageRating) {
        List<Map<String, Object>> leaders = values.entrySet().stream()
                .filter(entry -> averageRating
                        ? entry.getValue().appearances > 0
                        : entry.getValue().assists > 0)
                .map(entry -> {
                    ScorerAccumulator accumulator = entry.getValue();
                    Map<String, Object> row = identityRow(entry.getKey(), accumulator.teamId,
                            accumulator.teamName, humans, teamNames);
                    row.put("appearances", accumulator.appearances);
                    row.put("value", averageRating
                            ? round(accumulator.ratingTotal / accumulator.appearances, 2)
                            : accumulator.assists);
                    row.put("per90", averageRating ? null
                            : round((double) accumulator.assists / Math.max(1, accumulator.appearances), 2));
                    return row;
                })
                .sorted(leaderComparator("value", "appearances"))
                .limit(limit)
                .collect(Collectors.toCollection(ArrayList::new));
        addRanks(leaders);
        return category(key, title, unit, leaders);
    }

    private Map<String, Object> seasonStatCategory(String key, String title, String unit,
                                                    Map<Long, SeasonStatAccumulator> values,
                                                    Map<Long, Human> humans, Map<Long, String> teamNames,
                                                    int limit,
                                                    java.util.function.ToDoubleFunction<SeasonStatAccumulator> getter) {
        List<Map<String, Object>> leaders = values.entrySet().stream()
                .map(entry -> {
                    SeasonStatAccumulator accumulator = entry.getValue();
                    double value = getter.applyAsDouble(accumulator);
                    Map<String, Object> row = identityRow(entry.getKey(), accumulator.teamId,
                            null, humans, teamNames);
                    row.put("appearances", accumulator.appearances);
                    row.put("minutes", accumulator.minutes);
                    row.put("value", round(value, 1));
                    row.put("per90", accumulator.minutes > 0 ? round(value * 90.0 / accumulator.minutes, 2) : 0.0);
                    return row;
                })
                .filter(row -> ((Number) row.get("value")).doubleValue() > 0)
                .sorted(leaderComparator("value", "appearances"))
                .limit(limit)
                .collect(Collectors.toCollection(ArrayList::new));
        addRanks(leaders);
        return category(key, title, unit, leaders);
    }

    private Map<String, Object> category(String key, String title, String unit,
                                          List<Map<String, Object>> leaders) {
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("key", key);
        category.put("title", title);
        category.put("unit", unit);
        category.put("leaders", leaders);
        return category;
    }

    private Map<String, Object> identityRow(long playerId, long fallbackTeamId, String fallbackTeamName,
                                             Map<Long, Human> humans, Map<Long, String> teamNames) {
        Human human = humans.get(playerId);
        long teamId = human != null && human.getTeamId() != null ? human.getTeamId() : fallbackTeamId;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("playerId", playerId);
        row.put("playerName", human == null ? "Unknown" : human.getName());
        row.put("teamId", teamId);
        row.put("teamName", teamNames.getOrDefault(teamId,
                fallbackTeamName == null || fallbackTeamName.isBlank() ? "Free agent" : fallbackTeamName));
        return row;
    }

    private Comparator<Map<String, Object>> leaderComparator(String primary, String secondary) {
        return Comparator.<Map<String, Object>>comparingDouble(
                        row -> ((Number) row.get(primary)).doubleValue()).reversed()
                .thenComparing(Comparator.<Map<String, Object>>comparingDouble(
                        row -> ((Number) row.get(secondary)).doubleValue()).reversed())
                .thenComparing(row -> String.valueOf(row.get("playerName")));
    }

    private void addRanks(List<Map<String, Object>> rows) {
        for (int index = 0; index < rows.size(); index++) rows.get(index).put("rank", index + 1);
    }

    private static class GoldenBootAccumulator {
        int appearances;
        int goals;
        int assists;
        int firstLeagueGoals;
        int secondLeagueGoals;
        double weightedGoalPoints;
        double weightedAssistPoints;
        double awardPoints;
        long teamId;
        String teamName;

        void add(Scorer scorer, double leagueMultiplier, double goalWeight, double assistWeight) {
            appearances++;
            goals += scorer.getGoals();
            assists += scorer.getAssists();
            weightedGoalPoints += scorer.getGoals() * goalWeight * leagueMultiplier;
            weightedAssistPoints += scorer.getAssists() * assistWeight * leagueMultiplier;
            awardPoints = weightedGoalPoints + weightedAssistPoints;
            teamId = scorer.getTeamId();
            teamName = scorer.getTeamName();
            if (scorer.getCompetitionTypeId() == 1) firstLeagueGoals += scorer.getGoals();
            else if (scorer.getCompetitionTypeId() == 3) secondLeagueGoals += scorer.getGoals();
        }
    }

    private static class ScorerAccumulator {
        int appearances;
        int goals;
        int assists;
        double ratingTotal;
        long teamId;
        String teamName;

        void add(Scorer scorer) {
            appearances++;
            goals += scorer.getGoals();
            assists += scorer.getAssists();
            ratingTotal += scorer.getRating();
            teamId = scorer.getTeamId();
            teamName = scorer.getTeamName();
        }
    }

    private static class SeasonStatAccumulator {
        int appearances;
        int minutes;
        long teamId;
        double passesCompleted;
        double chancesCreated;
        double shots;
        double tackles;
        double pressures;
        double defensiveActions;

        void add(PlayerSeasonStat stat) {
            appearances += stat.getAppearances();
            minutes += stat.getMinutes();
            teamId = stat.getTeamId();
            passesCompleted += stat.getPassesCompleted();
            chancesCreated += stat.getChancesCreated();
            shots += stat.getShots();
            tackles += stat.getTackles();
            pressures += stat.getPressures();
            defensiveActions += stat.getDefensiveActions();
        }
    }

    // ==================== TEAM DATA HUB ====================

    public TeamDataHubStats getTeamDataHubStats(long teamId, int seasonNumber) {

        TeamDataHubStats stats = new TeamDataHubStats();
        stats.setTeamId(teamId);
        stats.setTeamName(teamRepository.findNameById(teamId));
        stats.setSeasonNumber(seasonNumber);

        // Get all scorer entries for this team+season (one per player per match)
        List<Scorer> teamScorers = scorerRepository.findAllByTeamIdAndSeasonNumber(teamId, seasonNumber);

        if (teamScorers.isEmpty()) {
            return stats;
        }

        // Identify unique matches by tracking transitions between match blocks.
        // Scorers for the same match share the same opponent, competition, and scores.
        // When these change, we know a new match started.
        Set<String> processedMatches = new LinkedHashSet<>();
        Map<String, Integer> matchTeamScores = new LinkedHashMap<>();
        Map<String, Integer> matchOpponentScores = new LinkedHashMap<>();

        // Track unique matches by building keys from sequential scorer data
        // Since scorers are inserted per match, entries for same match share teamScore/opponentScore/opponent
        String lastMatchKey = "";
        int matchCounter = 0;
        for (Scorer s : teamScorers) {
            if (s.getTeamScore() < 0) continue;
            String candidateKey = s.getOpponentTeamId() + "_" + s.getCompetitionId() + "_" + s.getTeamScore() + "_" + s.getOpponentScore();
            if (!candidateKey.equals(lastMatchKey)) {
                matchCounter++;
                lastMatchKey = candidateKey;
            }
            String matchKey = candidateKey + "_" + matchCounter;
            processedMatches.add(matchKey);
            matchTeamScores.put(matchKey, s.getTeamScore());
            matchOpponentScores.put(matchKey, s.getOpponentScore());
        }

        int totalMatches = processedMatches.size();
        int wins = 0, draws = 0, losses = 0, goalsScored = 0, goalsConceded = 0, cleanSheets = 0;

        List<String> formList = new ArrayList<>();
        for (String mk : processedMatches) {
            int ts = matchTeamScores.get(mk);
            int os = matchOpponentScores.get(mk);
            goalsScored += ts;
            goalsConceded += os;
            if (os == 0) cleanSheets++;
            if (ts > os) { wins++; formList.add("W"); }
            else if (ts == os) { draws++; formList.add("D"); }
            else { losses++; formList.add("L"); }
        }

        stats.setTotalMatches(totalMatches);
        stats.setWins(wins);
        stats.setDraws(draws);
        stats.setLosses(losses);
        stats.setGoalsScored(goalsScored);
        stats.setGoalsConceded(goalsConceded);
        stats.setGoalsPerGame(totalMatches > 0 ? Math.round((double) goalsScored / totalMatches * 100.0) / 100.0 : 0);
        stats.setConcededPerGame(totalMatches > 0 ? Math.round((double) goalsConceded / totalMatches * 100.0) / 100.0 : 0);
        stats.setCleanSheets(cleanSheets);
        stats.setCleanSheetPercentage(totalMatches > 0 ? Math.round((double) cleanSheets / totalMatches * 100.0) / 100.0 : 0);
        stats.setWinPercentage(totalMatches > 0 ? Math.round((double) wins / totalMatches * 100.0) / 100.0 : 0);

        // Recent form (last 5)
        int formSize = formList.size();
        stats.setRecentForm(formList.subList(Math.max(0, formSize - 5), formSize));

        // Player-level aggregations
        Map<Long, Integer> playerGoals = new HashMap<>();
        Map<Long, Integer> playerAssists = new HashMap<>();
        Map<Long, Double> playerRatingSum = new HashMap<>();
        Map<Long, Integer> playerMatchCount = new HashMap<>();
        double totalRatingSum = 0;
        int totalRatingCount = 0;
        int totalAssists = 0;

        for (Scorer s : teamScorers) {
            if (s.getTeamScore() < 0) continue;
            playerGoals.merge(s.getPlayerId(), s.getGoals(), Integer::sum);
            playerAssists.merge(s.getPlayerId(), s.getAssists(), Integer::sum);
            playerRatingSum.merge(s.getPlayerId(), s.getRating(), Double::sum);
            playerMatchCount.merge(s.getPlayerId(), 1, Integer::sum);
            totalRatingSum += s.getRating();
            totalRatingCount++;
            totalAssists += s.getAssists();
        }

        stats.setAvgTeamRating(totalRatingCount > 0 ? Math.round(totalRatingSum / totalRatingCount * 100.0) / 100.0 : 0);
        stats.setTotalAssists(totalAssists);
        stats.setAssistsPerGame(totalMatches > 0 ? Math.round((double) totalAssists / totalMatches * 100.0) / 100.0 : 0);

        // Top scorer
        long topScorerId = 0;
        int topGoals = 0;
        for (Map.Entry<Long, Integer> e : playerGoals.entrySet()) {
            if (e.getValue() > topGoals) { topGoals = e.getValue(); topScorerId = e.getKey(); }
        }
        if (topScorerId > 0) {
            stats.setTopScorer(humanRepository.findById(topScorerId).map(Human::getName).orElse("Unknown"));
            stats.setTopScorerGoals(topGoals);
        }

        // Top assister
        long topAssisterId = 0;
        int topAssists = 0;
        for (Map.Entry<Long, Integer> e : playerAssists.entrySet()) {
            if (e.getValue() > topAssists) { topAssists = e.getValue(); topAssisterId = e.getKey(); }
        }
        if (topAssisterId > 0) {
            stats.setTopAssister(humanRepository.findById(topAssisterId).map(Human::getName).orElse("Unknown"));
            stats.setTopAssisterAssists(topAssists);
        }

        // Highest rated player
        long highestRatedId = 0;
        double highestAvgRating = 0;
        for (Map.Entry<Long, Double> e : playerRatingSum.entrySet()) {
            int count = playerMatchCount.getOrDefault(e.getKey(), 1);
            double avg = e.getValue() / count;
            if (avg > highestAvgRating) { highestAvgRating = avg; highestRatedId = e.getKey(); }
        }
        if (highestRatedId > 0) {
            stats.setHighestRatedPlayer(humanRepository.findById(highestRatedId).map(Human::getName).orElse("Unknown"));
            stats.setHighestRating(Math.round(highestAvgRating * 100.0) / 100.0);
        }

        // League averages: compute from all teams in the same season
        List<Scorer> allSeasonScorers =
                scorerRepository.findAllBySeasonNumberAndRoundNumberGreaterThan(seasonNumber, 0);
        double leagueTotalRating = 0;
        int leagueTotalRatingCount = 0;
        int leagueTotalAssists = 0;

        String lastLeagueKey = "";
        int leagueMatchCounter = 0;
        Map<Long, Map<String, int[]>> teamMatchData = new HashMap<>();

        for (Scorer s : allSeasonScorers) {
            if (s.getTeamScore() < 0) continue;
            long tid = s.getTeamId();

            String candidateKey = tid + "_" + s.getOpponentTeamId() + "_" + s.getCompetitionId() + "_" + s.getTeamScore() + "_" + s.getOpponentScore();
            if (!candidateKey.equals(lastLeagueKey)) {
                leagueMatchCounter++;
                lastLeagueKey = candidateKey;
            }
            String matchKey = candidateKey + "_" + leagueMatchCounter;

            teamMatchData.computeIfAbsent(tid, k -> new LinkedHashMap<>());
            teamMatchData.get(tid).putIfAbsent(matchKey, new int[]{s.getTeamScore(), s.getOpponentScore()});

            leagueTotalRating += s.getRating();
            leagueTotalRatingCount++;
            leagueTotalAssists += s.getAssists();
        }

        int leagueTeamCount = teamMatchData.size();
        double totalLeagueGPG = 0, totalLeagueCPG = 0, totalLeagueCSPct = 0, totalLeagueWinPct = 0;
        int totalLeagueMatches = 0;

        for (Map.Entry<Long, Map<String, int[]>> teamEntry : teamMatchData.entrySet()) {
            Map<String, int[]> matches = teamEntry.getValue();
            int tMatches = matches.size();
            int tGoals = 0, tConceded = 0, tClean = 0, tWins = 0;
            for (int[] scores : matches.values()) {
                tGoals += scores[0];
                tConceded += scores[1];
                if (scores[1] == 0) tClean++;
                if (scores[0] > scores[1]) tWins++;
            }
            if (tMatches > 0) {
                totalLeagueGPG += (double) tGoals / tMatches;
                totalLeagueCPG += (double) tConceded / tMatches;
                totalLeagueCSPct += (double) tClean / tMatches;
                totalLeagueWinPct += (double) tWins / tMatches;
                totalLeagueMatches += tMatches;
            }
        }

        if (leagueTeamCount > 0) {
            stats.setLeagueAvgGoalsPerGame(Math.round(totalLeagueGPG / leagueTeamCount * 100.0) / 100.0);
            stats.setLeagueAvgConcededPerGame(Math.round(totalLeagueCPG / leagueTeamCount * 100.0) / 100.0);
            stats.setLeagueAvgCleanSheetPct(Math.round(totalLeagueCSPct / leagueTeamCount * 100.0) / 100.0);
            stats.setLeagueAvgWinPct(Math.round(totalLeagueWinPct / leagueTeamCount * 100.0) / 100.0);
        }
        if (leagueTotalRatingCount > 0) {
            stats.setLeagueAvgRating(Math.round(leagueTotalRating / leagueTotalRatingCount * 100.0) / 100.0);
        }
        if (totalLeagueMatches > 0) {
            stats.setLeagueAvgAssistsPerGame(Math.round((double) leagueTotalAssists / totalLeagueMatches * 100.0) / 100.0);
        }

        return stats;
    }

    // ==================== CHAMPIONSHIP STATS ====================

    /**
     * Aggregates the match_stats table across every match a team played in this
     * (competition, season) and returns per-category leaderboards. One round-trip
     * gives the frontend everything it needs to render an expandable "championship
     * stats" page (Most Possession, Most Cards, Best xG, Most Shots, etc).
     */
    public Map<String, Object> getChampionshipStats(long competitionId, int season) {
        List<MatchStats> matches = matchStatsRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, season);

        // Aggregate per team
        Map<Long, TeamStatAccumulator> agg = new LinkedHashMap<>();
        for (MatchStats m : matches) {
            agg.computeIfAbsent(m.getTeam1Id(), TeamStatAccumulator::new).addHomeSide(m);
            agg.computeIfAbsent(m.getTeam2Id(), TeamStatAccumulator::new).addAwaySide(m);
        }

        // Ensure every team currently in this league appears in the leaderboards,
        // even if it hasn't played a tracked match yet (financial leaderboards
        // are meaningful at season start before any match-stats exist).
        List<Team> teamsInComp = teamRepository.findAllByCompetitionId(competitionId);
        for (Team t : teamsInComp) {
            agg.computeIfAbsent(t.getId(), TeamStatAccumulator::new);
        }

        // Resolve names + colors once
        Map<Long, Team> teamMap = teamRepository.findAllById(agg.keySet()).stream()
                .collect(Collectors.toMap(Team::getId, t -> t));

        // ===== Financials (per team, this season) =====
        // Group all transfers of the season by buy/sell team so we can total
        // money spent and earned without a per-team query.
        List<Transfer> seasonTransfers = transferRepository.findAllBySeasonNumber((long) season);
        Map<Long, Long> spentByTeam = new HashMap<>();
        Map<Long, Long> earnedByTeam = new HashMap<>();
        for (Transfer t : seasonTransfers) {
            spentByTeam.merge(t.getBuyTeamId(),  t.getPlayerTransferValue(), Long::sum);
            earnedByTeam.merge(t.getSellTeamId(), t.getPlayerTransferValue(), Long::sum);
        }
        // Pre-load all human/staff wages grouped by teamId in one query
        Map<Long, Long> monthlyWagesByTeam = humanRepository.findAll().stream()
                .filter(h -> !h.isRetired() && h.getTeamId() != null && h.getTeamId() > 0)
                .collect(Collectors.groupingBy(Human::getTeamId, Collectors.summingLong(Human::getWage)));

        // Build per-team stat records (averages + totals) ready for sorting
        List<Map<String, Object>> teamRows = new ArrayList<>();
        for (TeamStatAccumulator a : agg.values()) {
            Team t = teamMap.get(a.teamId);
            int mc = Math.max(a.matchCount, 1); // protect against /0 — averages will be 0 if no matches
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("teamId", a.teamId);
            row.put("teamName", t != null ? t.getName() : "?");
            row.put("teamColor1", t != null ? t.getColor1() : null);
            row.put("matches", a.matchCount);
            row.put("avgPossession",   round(a.possession   / mc, 1));
            row.put("avgPassAccuracy", round(a.passAccuracy / mc, 1));
            row.put("avgShots",        round(a.shots        / (double) mc, 1));
            row.put("avgShotsOnTarget",round(a.shotsOnTarget/ (double) mc, 1));
            row.put("avgCorners",      round(a.corners      / (double) mc, 1));
            row.put("avgFouls",        round(a.fouls        / (double) mc, 1));
            row.put("avgOffsides",     round(a.offsides     / (double) mc, 1));
            row.put("avgTackles",      round(a.tackles      / (double) mc, 1));
            row.put("avgInterceptions",round(a.interceptions/ (double) mc, 1));
            row.put("avgClearances",   round(a.clearances   / (double) mc, 1));
            row.put("avgDuelsWon",     round(a.duelsWon     / (double) mc, 1));
            row.put("avgAerialDuels",  round(a.aerialDuels  / (double) mc, 1));
            row.put("avgBigChances",   round(a.bigChances   / (double) mc, 1));
            row.put("avgXg",           round(a.xg           / 100.0 / mc, 2));
            row.put("totalYellowCards",a.yellow);
            row.put("totalRedCards",   a.red);
            row.put("totalGoals",      a.goals);
            row.put("totalConceded",   a.conceded);

            // Financials (snapshot of current state + transfer activity this season)
            long monthly = monthlyWagesByTeam.getOrDefault(a.teamId, 0L);
            long spent   = spentByTeam.getOrDefault(a.teamId, 0L);
            long earned  = earnedByTeam.getOrDefault(a.teamId, 0L);
            row.put("transferBudget",  t != null ? t.getTransferBudget() : 0L);
            row.put("totalFinances",   t != null ? t.getTotalFinances()  : 0L);
            row.put("monthlyWages",    monthly);
            row.put("annualWages",     monthly * 12);
            row.put("transferSpent",   spent);
            row.put("transferEarned",  earned);
            row.put("transferNet",     spent - earned); // + = bought more than sold
            row.put("transferProfit",  earned - spent); // + = sold more than bought

            teamRows.add(row);
        }

        // For each category, produce a sorted leaderboard of {teamId, value, displayValue}
        Map<String, Object> categories = new LinkedHashMap<>();
        categories.put("possession",      leaderboard(teamRows, "avgPossession",   "% avg",  true));
        categories.put("passAccuracy",    leaderboard(teamRows, "avgPassAccuracy", "% avg",  true));
        categories.put("shots",           leaderboard(teamRows, "avgShots",        "per match", true));
        categories.put("shotsOnTarget",   leaderboard(teamRows, "avgShotsOnTarget","per match", true));
        categories.put("corners",         leaderboard(teamRows, "avgCorners",      "per match", true));
        categories.put("bigChances",      leaderboard(teamRows, "avgBigChances",   "per match", true));
        categories.put("xg",              leaderboard(teamRows, "avgXg",           "per match", true));
        categories.put("goalsScored",     leaderboard(teamRows, "totalGoals",      "total",   true));
        categories.put("goalsConceded",   leaderboard(teamRows, "totalConceded",   "total",   false)); // asc = best defense
        categories.put("yellowCards",     leaderboard(teamRows, "totalYellowCards","total",   true));
        categories.put("redCards",        leaderboard(teamRows, "totalRedCards",   "total",   true));
        categories.put("fouls",           leaderboard(teamRows, "avgFouls",        "per match", true));
        categories.put("offsides",        leaderboard(teamRows, "avgOffsides",     "per match", true));
        categories.put("tackles",         leaderboard(teamRows, "avgTackles",      "per match", true));
        categories.put("interceptions",   leaderboard(teamRows, "avgInterceptions","per match", true));
        categories.put("clearances",      leaderboard(teamRows, "avgClearances",   "per match", true));
        categories.put("duelsWon",        leaderboard(teamRows, "avgDuelsWon",     "per match", true));
        categories.put("aerialDuels",     leaderboard(teamRows, "avgAerialDuels",  "per match", true));

        // === Financial leaderboards ===
        categories.put("transferBudget",  leaderboard(teamRows, "transferBudget",  "€ available",  true));
        categories.put("totalFinances",   leaderboard(teamRows, "totalFinances",   "€ in bank",    true));
        categories.put("monthlyWages",    leaderboard(teamRows, "monthlyWages",    "€/month",      true));
        categories.put("annualWages",     leaderboard(teamRows, "annualWages",     "€/year",       true));
        categories.put("transferSpent",   leaderboard(teamRows, "transferSpent",   "€ this season",true));
        categories.put("transferEarned",  leaderboard(teamRows, "transferEarned",  "€ this season",true));
        categories.put("transferProfit",  leaderboard(teamRows, "transferProfit",  "€ net profit", true));

        Competition comp = competitionRepository.findById(competitionId).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("competitionId", competitionId);
        result.put("competitionName", comp != null ? comp.getName() : "");
        result.put("season", season);
        result.put("matchesPlayed", matches.size());
        result.put("teamsTracked", teamRows.size());
        result.put("teams", teamRows);          // full per-team table
        result.put("categories", categories);   // sorted leaderboards per metric
        return result;
    }

    /**
     * Sorts the team rows by the given numeric field and returns a small object
     * per row containing teamId / name / color / value formatted for display.
     */
    private List<Map<String, Object>> leaderboard(List<Map<String, Object>> rows, String field,
                                                   String unit, boolean descending) {
        return rows.stream()
                .sorted((a, b) -> {
                    double va = ((Number) a.getOrDefault(field, 0)).doubleValue();
                    double vb = ((Number) b.getOrDefault(field, 0)).doubleValue();
                    return descending ? Double.compare(vb, va) : Double.compare(va, vb);
                })
                .map(r -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("teamId", r.get("teamId"));
                    e.put("teamName", r.get("teamName"));
                    e.put("teamColor1", r.get("teamColor1"));
                    e.put("value", r.get(field));
                    e.put("unit", unit);
                    return e;
                })
                .toList();
    }

    private double round(double v, int decimals) {
        double mul = Math.pow(10, decimals);
        return Math.round(v * mul) / mul;
    }

    /**
     * Internal accumulator: sums up everything one team contributed across all
     * their matches in this (competition, season). All averages are computed
     * on the way out.
     */
    private static class TeamStatAccumulator {
        final long teamId;
        int matchCount = 0;
        double possession = 0, passAccuracy = 0;
        int shots = 0, shotsOnTarget = 0, corners = 0, fouls = 0, offsides = 0;
        int tackles = 0, interceptions = 0, clearances = 0;
        int duelsWon = 0, aerialDuels = 0, bigChances = 0;
        int yellow = 0, red = 0, goals = 0, conceded = 0;
        long xg = 0; // stored * 100 in DB

        TeamStatAccumulator(long teamId) { this.teamId = teamId; }

        void addHomeSide(MatchStats m) {
            matchCount++;
            possession    += m.getHomePossession();
            passAccuracy  += m.getHomePassAccuracy();
            shots         += m.getHomeShots();
            shotsOnTarget += m.getHomeShotsOnTarget();
            corners       += m.getHomeCorners();
            fouls         += m.getHomeFouls();
            offsides      += m.getHomeOffsides();
            tackles       += m.getHomeTackles();
            interceptions += m.getHomeInterceptions();
            clearances    += m.getHomeClearances();
            duelsWon      += m.getHomeDuelsWon();
            aerialDuels   += m.getHomeAerialDuelsWon();
            bigChances    += m.getHomeBigChances();
            yellow        += m.getHomeYellowCards();
            red           += m.getHomeRedCards();
            goals         += m.getHomeGoals();
            conceded      += m.getAwayGoals();
            xg            += m.getHomeXg();
        }

        void addAwaySide(MatchStats m) {
            matchCount++;
            possession    += m.getAwayPossession();
            passAccuracy  += m.getAwayPassAccuracy();
            shots         += m.getAwayShots();
            shotsOnTarget += m.getAwayShotsOnTarget();
            corners       += m.getAwayCorners();
            fouls         += m.getAwayFouls();
            offsides      += m.getAwayOffsides();
            tackles       += m.getAwayTackles();
            interceptions += m.getAwayInterceptions();
            clearances    += m.getAwayClearances();
            duelsWon      += m.getAwayDuelsWon();
            aerialDuels   += m.getAwayAerialDuelsWon();
            bigChances    += m.getAwayBigChances();
            yellow        += m.getAwayYellowCards();
            red           += m.getAwayRedCards();
            goals         += m.getAwayGoals();
            conceded      += m.getHomeGoals();
            xg            += m.getAwayXg();
        }
    }

    // ==================== ALL-TIME CHAMPIONS ====================

    /**
     * Ranking of teams by total titles won, broken down by competition type.
     * Sorted by totalTitles descending.
     */
    public List<Map<String, Object>> getAllTimeChampions() {
        List<CompetitionHistory> allHistory = competitionHistoryRepository.findAll();

        // Group winners (position 1) by teamId
        Map<Long, List<CompetitionHistory>> teamWins = allHistory.stream()
                .filter(h -> h.getLastPosition() == 1)
                .collect(Collectors.groupingBy(CompetitionHistory::getTeamId));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, List<CompetitionHistory>> entry : teamWins.entrySet()) {
            long teamId = entry.getKey();
            List<CompetitionHistory> wins = entry.getValue();

            Map<String, Object> teamData = new LinkedHashMap<>();
            teamData.put("teamId", teamId);
            teamData.put("teamName", teamRepository.findNameById(teamId));

            int leagueTitles = 0, cupTitles = 0, secondLeagueTitles = 0, locTitles = 0, starsCupTitles = 0;
            List<Map<String, Object>> titleList = new ArrayList<>();

            for (CompetitionHistory h : wins) {
                Map<String, Object> title = new LinkedHashMap<>();
                title.put("season", h.getSeasonNumber());
                title.put("competitionName", h.getCompetitionName());
                title.put("competitionTypeId", h.getCompetitionTypeId());
                titleList.add(title);

                switch ((int) h.getCompetitionTypeId()) {
                    case 1: leagueTitles++; break;
                    case 2: cupTitles++; break;
                    case 3: secondLeagueTitles++; break;
                    case 4: locTitles++; break;
                    case 5: starsCupTitles++; break;
                }
            }

            teamData.put("totalTitles", wins.size());
            teamData.put("leagueTitles", leagueTitles);
            teamData.put("cupTitles", cupTitles);
            teamData.put("secondLeagueTitles", secondLeagueTitles);
            teamData.put("locTitles", locTitles);
            teamData.put("starsCupTitles", starsCupTitles);
            teamData.put("titles", titleList);
            result.add(teamData);
        }

        result.sort((a, b) -> Integer.compare((int) b.get("totalTitles"), (int) a.get("totalTitles")));
        return result;
    }

    // ==================== COMPETITION STATISTICS ====================

    /**
     * Detailed statistics for a specific competition and season:
     * - topScorers: top 20 by goals
     * - topAssisters: top 20 by assists
     * - topRated: top 20 by avg rating (min 5 matches)
     * - cleanSheets: teams ranked by clean sheets
     * - disciplinary: top 20 players by yellow + red cards
     */
    public Map<String, Object> getCompetitionStats(long competitionId, int seasonNumber) {
        Map<String, Object> result = new LinkedHashMap<>();

        Competition competition = competitionRepository.findById(competitionId).orElse(null);
        result.put("competitionId", competitionId);
        result.put("competitionName", competition != null ? competition.getName() : "Unknown");
        result.put("seasonNumber", seasonNumber);

        // --- Player stats from Scorer ---
        List<Scorer> scorers = scorerRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, seasonNumber);

        Map<Long, int[]> playerAgg = new LinkedHashMap<>(); // playerId -> [goals, assists, matches]
        Map<Long, Double> playerRatingSum = new LinkedHashMap<>();
        Map<Long, Long> playerTeamId = new LinkedHashMap<>();
        Map<Long, String> playerTeamName = new LinkedHashMap<>();

        for (Scorer s : scorers) {
            // Placeholder rows are created at season start (teamScore=-1, opponentTeamId=-1)
            // so the leaderboard wires up before any match is played. They must NOT be
            // counted as appearances — otherwise every player shows M=1 before kickoff.
            if (s.getTeamScore() < 0 || s.getOpponentTeamId() < 0) continue;

            long pid = s.getPlayerId();
            playerAgg.computeIfAbsent(pid, k -> new int[3]);
            playerAgg.get(pid)[0] += s.getGoals();
            playerAgg.get(pid)[1] += s.getAssists();
            playerAgg.get(pid)[2] += 1;
            playerRatingSum.merge(pid, s.getRating(), Double::sum);
            playerTeamId.putIfAbsent(pid, s.getTeamId());
            playerTeamName.putIfAbsent(pid, s.getTeamName());
        }

        // Build player entries
        List<Map<String, Object>> playerEntries = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : playerAgg.entrySet()) {
            long pid = e.getKey();
            int[] agg = e.getValue();
            Map<String, Object> pe = new LinkedHashMap<>();
            pe.put("playerId", pid);
            pe.put("playerName", humanRepository.findById(pid).map(Human::getName).orElse("Unknown"));
            pe.put("position", humanRepository.findById(pid).map(Human::getPosition).orElse(""));
            pe.put("teamId", playerTeamId.getOrDefault(pid, 0L));
            pe.put("teamName", playerTeamName.getOrDefault(pid, "Unknown"));
            pe.put("goals", agg[0]);
            pe.put("assists", agg[1]);
            pe.put("matches", agg[2]);
            pe.put("avgRating", agg[2] > 0 ? Math.round(playerRatingSum.getOrDefault(pid, 0.0) / agg[2] * 100.0) / 100.0 : 0);
            playerEntries.add(pe);
        }

        // Top scorers
        result.put("topScorers", playerEntries.stream()
                .sorted((a, b) -> Integer.compare((int) b.get("goals"), (int) a.get("goals")))
                .limit(20).toList());

        // Top assisters
        result.put("topAssisters", playerEntries.stream()
                .sorted((a, b) -> Integer.compare((int) b.get("assists"), (int) a.get("assists")))
                .limit(20).toList());

        // Top rated (min 5 matches)
        result.put("topRated", playerEntries.stream()
                .filter(pe -> (int) pe.get("matches") >= 5)
                .sorted((a, b) -> Double.compare((double) b.get("avgRating"), (double) a.get("avgRating")))
                .limit(20).toList());

        // --- Clean sheets per team ---
        // Group scorers by team, then count matches where opponent scored 0
        Map<Long, String> teamNames = new LinkedHashMap<>();
        Map<Long, Set<String>> teamMatchKeys = new LinkedHashMap<>();
        Map<Long, Integer> teamCleanSheets = new LinkedHashMap<>();
        Map<Long, Integer> teamMatchCounts = new LinkedHashMap<>();

        String lastKey = "";
        int counter = 0;
        for (Scorer s : scorers) {
            if (s.getTeamScore() < 0) continue;
            long tid = s.getTeamId();
            teamNames.putIfAbsent(tid, s.getTeamName());

            String candidateKey = tid + "_" + s.getOpponentTeamId() + "_" + s.getTeamScore() + "_" + s.getOpponentScore();
            if (!candidateKey.equals(lastKey)) { counter++; lastKey = candidateKey; }
            String matchKey = candidateKey + "_" + counter;

            Set<String> keys = teamMatchKeys.computeIfAbsent(tid, k -> new LinkedHashSet<>());
            if (keys.add(matchKey)) {
                teamMatchCounts.merge(tid, 1, Integer::sum);
                if (s.getOpponentScore() == 0) {
                    teamCleanSheets.merge(tid, 1, Integer::sum);
                }
            }
        }

        List<Map<String, Object>> cleanSheetRanking = new ArrayList<>();
        for (Long tid : teamNames.keySet()) {
            Map<String, Object> cs = new LinkedHashMap<>();
            cs.put("teamId", tid);
            cs.put("teamName", teamNames.get(tid));
            int sheets = teamCleanSheets.getOrDefault(tid, 0);
            int matches = teamMatchCounts.getOrDefault(tid, 0);
            cs.put("cleanSheets", sheets);
            cs.put("matches", matches);
            cs.put("percentage", matches > 0 ? Math.round((double) sheets / matches * 100.0) / 100.0 : 0);
            cleanSheetRanking.add(cs);
        }
        cleanSheetRanking.sort((a, b) -> Integer.compare((int) b.get("cleanSheets"), (int) a.get("cleanSheets")));
        result.put("cleanSheets", cleanSheetRanking);

        // --- Disciplinary (cards from MatchEvent) ---
        List<MatchEvent> events = matchEventRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, seasonNumber);

        Map<Long, int[]> playerCards = new LinkedHashMap<>(); // playerId -> [yellow, red]
        Map<Long, String> cardPlayerNames = new LinkedHashMap<>();
        Map<Long, Long> cardPlayerTeamIds = new LinkedHashMap<>();

        for (MatchEvent event : events) {
            if ("yellow_card".equals(event.getEventType())) {
                playerCards.computeIfAbsent(event.getPlayerId(), k -> new int[2])[0]++;
                cardPlayerNames.putIfAbsent(event.getPlayerId(), event.getPlayerName());
                cardPlayerTeamIds.putIfAbsent(event.getPlayerId(), event.getTeamId());
            } else if ("red_card".equals(event.getEventType())) {
                playerCards.computeIfAbsent(event.getPlayerId(), k -> new int[2])[1]++;
                cardPlayerNames.putIfAbsent(event.getPlayerId(), event.getPlayerName());
                cardPlayerTeamIds.putIfAbsent(event.getPlayerId(), event.getTeamId());
            }
        }

        List<Map<String, Object>> disciplinary = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : playerCards.entrySet()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("playerId", e.getKey());
            d.put("playerName", cardPlayerNames.getOrDefault(e.getKey(), "Unknown"));
            d.put("teamId", cardPlayerTeamIds.getOrDefault(e.getKey(), 0L));
            d.put("teamName", teamRepository.findNameById(cardPlayerTeamIds.getOrDefault(e.getKey(), 0L)));
            d.put("yellowCards", e.getValue()[0]);
            d.put("redCards", e.getValue()[1]);
            d.put("totalCards", e.getValue()[0] + e.getValue()[1]);
            disciplinary.add(d);
        }
        disciplinary.sort((a, b) -> Integer.compare((int) b.get("totalCards"), (int) a.get("totalCards")));
        result.put("disciplinary", disciplinary.stream().limit(20).toList());

        return result;
    }

    // ==================== PLAYER FORM ====================

    /**
     * The player's last 5 match appearances with individual performance data
     * + form trend (last 5 vs previous 5 avg ratings).
     */
    public Map<String, Object> getPlayerForm(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();

        Human player = humanRepository.findById(playerId).orElse(null);
        result.put("playerId", playerId);
        result.put("playerName", player != null ? player.getName() : "Unknown");
        result.put("position", player != null ? player.getPosition() : "");
        result.put("teamName", player != null && player.getTeamId() != null
                ? teamRepository.findNameById(player.getTeamId()) : "Free Agent");

        // Get last 5 appearances (most recent by ID descending)
        List<Scorer> recentMatches = scorerRepository.findTop5ByPlayerIdOrderByIdDesc(playerId);

        List<Map<String, Object>> formList = new ArrayList<>();
        double ratingSum = 0;
        int totalGoals = 0, totalAssists = 0;

        for (Scorer s : recentMatches) {
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("opponentName", s.getOpponentTeamName());
            match.put("competitionName", s.getCompetitionName());
            match.put("seasonNumber", s.getSeasonNumber());
            match.put("teamScore", s.getTeamScore());
            match.put("opponentScore", s.getOpponentScore());

            String matchResult;
            if (s.getTeamScore() > s.getOpponentScore()) matchResult = "W";
            else if (s.getTeamScore() == s.getOpponentScore()) matchResult = "D";
            else matchResult = "L";
            match.put("result", matchResult);

            match.put("goals", s.getGoals());
            match.put("assists", s.getAssists());
            match.put("rating", Math.round(s.getRating() * 10.0) / 10.0);
            match.put("wasSubstitute", s.isSubstitute());

            formList.add(match);
            ratingSum += s.getRating();
            totalGoals += s.getGoals();
            totalAssists += s.getAssists();
        }

        // Reverse so oldest is first, newest is last (chronological order)
        java.util.Collections.reverse(formList);

        result.put("form", formList);
        result.put("matchCount", recentMatches.size());
        result.put("avgRating", recentMatches.isEmpty() ? 0 : Math.round(ratingSum / recentMatches.size() * 10.0) / 10.0);
        result.put("totalGoals", totalGoals);
        result.put("totalAssists", totalAssists);

        // Form trend: rating of last 5 vs previous 5
        List<Scorer> allMatches = scorerRepository.findAllByPlayerId(playerId);
        if (allMatches.size() >= 10) {
            double last5Avg = allMatches.subList(allMatches.size() - 5, allMatches.size()).stream()
                    .mapToDouble(Scorer::getRating).average().orElse(0);
            double prev5Avg = allMatches.subList(allMatches.size() - 10, allMatches.size() - 5).stream()
                    .mapToDouble(Scorer::getRating).average().orElse(0);
            String trend;
            if (last5Avg - prev5Avg > 3) trend = "IMPROVING";
            else if (prev5Avg - last5Avg > 3) trend = "DECLINING";
            else trend = "STABLE";
            result.put("trend", trend);
            result.put("last5Avg", Math.round(last5Avg * 10.0) / 10.0);
            result.put("prev5Avg", Math.round(prev5Avg * 10.0) / 10.0);
        } else {
            result.put("trend", "UNKNOWN");
        }

        return result;
    }

    // ==================== PLAYER COMPARISON ====================

    /**
     * Head-to-head comparison of two players across all seasons and competitions.
     */
    public Map<String, Object> comparePlayersHeadToHead(long playerId1, long playerId2) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("player1", buildComparisonProfile(playerId1));
        result.put("player2", buildComparisonProfile(playerId2));
        return result;
    }

    private Map<String, Object> buildComparisonProfile(long playerId) {
        Map<String, Object> profile = new LinkedHashMap<>();

        Human player = humanRepository.findById(playerId).orElse(null);
        profile.put("playerId", playerId);
        profile.put("playerName", player != null ? player.getName() : "Unknown");
        profile.put("position", player != null ? player.getPosition() : "");
        profile.put("age", player != null ? player.getAge() : 0);
        profile.put("rating", player != null ? Math.round(player.getRating() * 10.0) / 10.0 : 0);
        profile.put("teamName", player != null && player.getTeamId() != null
                ? teamRepository.findNameById(player.getTeamId()) : "Free Agent");
        profile.put("morale", player != null ? Math.round(player.getMorale()) : 0);
        profile.put("fitness", player != null ? Math.round(player.getFitness()) : 0);

        List<Scorer> allScorers = scorerRepository.findAllByPlayerId(playerId);

        // Career totals
        int totalMatches = allScorers.size();
        int totalGoals = allScorers.stream().mapToInt(Scorer::getGoals).sum();
        int totalAssists = allScorers.stream().mapToInt(Scorer::getAssists).sum();
        double avgRating = allScorers.stream().mapToDouble(Scorer::getRating).average().orElse(0);

        Map<String, Object> career = new LinkedHashMap<>();
        career.put("matches", totalMatches);
        career.put("goals", totalGoals);
        career.put("assists", totalAssists);
        career.put("avgRating", Math.round(avgRating * 10.0) / 10.0);
        career.put("goalsPerGame", totalMatches > 0 ? Math.round((double) totalGoals / totalMatches * 100.0) / 100.0 : 0);
        profile.put("career", career);

        // Current season stats
        int maxSeason = allScorers.stream().mapToInt(Scorer::getSeasonNumber).max().orElse(0);
        List<Scorer> currentSeasonScorers = allScorers.stream()
                .filter(s -> s.getSeasonNumber() == maxSeason).toList();

        Map<String, Object> currentSeason = new LinkedHashMap<>();
        currentSeason.put("season", maxSeason);
        currentSeason.put("matches", currentSeasonScorers.size());
        currentSeason.put("goals", currentSeasonScorers.stream().mapToInt(Scorer::getGoals).sum());
        currentSeason.put("assists", currentSeasonScorers.stream().mapToInt(Scorer::getAssists).sum());
        currentSeason.put("avgRating", Math.round(currentSeasonScorers.stream()
                .mapToDouble(Scorer::getRating).average().orElse(0) * 10.0) / 10.0);
        profile.put("currentSeason", currentSeason);

        // By competition type
        Map<String, Object> byType = new LinkedHashMap<>();
        Map<Integer, List<Scorer>> byCompType = allScorers.stream()
                .collect(Collectors.groupingBy(Scorer::getCompetitionTypeId));
        String[] typeNames = {"", "League", "Cup", "Second League", "League of Champions", "Stars Cup", "Super Cup"};
        for (Map.Entry<Integer, List<Scorer>> e : byCompType.entrySet()) {
            int typeId = e.getKey();
            List<Scorer> typeScorers = e.getValue();
            Map<String, Object> typeStats = new LinkedHashMap<>();
            typeStats.put("matches", typeScorers.size());
            typeStats.put("goals", typeScorers.stream().mapToInt(Scorer::getGoals).sum());
            typeStats.put("assists", typeScorers.stream().mapToInt(Scorer::getAssists).sum());
            typeStats.put("avgRating", Math.round(typeScorers.stream()
                    .mapToDouble(Scorer::getRating).average().orElse(0) * 10.0) / 10.0);
            String label = typeId >= 0 && typeId < typeNames.length ? typeNames[typeId] : "Other";
            byType.put(label, typeStats);
        }
        profile.put("byCompetitionType", byType);

        // Season-by-season breakdown
        Map<Integer, List<Scorer>> bySeason = allScorers.stream()
                .collect(Collectors.groupingBy(Scorer::getSeasonNumber));
        List<Map<String, Object>> seasonBreakdown = new ArrayList<>();
        for (Map.Entry<Integer, List<Scorer>> e : bySeason.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey)).toList()) {
            List<Scorer> ss = e.getValue();
            Map<String, Object> sb = new LinkedHashMap<>();
            sb.put("season", e.getKey());
            sb.put("matches", ss.size());
            sb.put("goals", ss.stream().mapToInt(Scorer::getGoals).sum());
            sb.put("assists", ss.stream().mapToInt(Scorer::getAssists).sum());
            sb.put("avgRating", Math.round(ss.stream().mapToDouble(Scorer::getRating).average().orElse(0) * 10.0) / 10.0);
            seasonBreakdown.add(sb);
        }
        profile.put("seasons", seasonBreakdown);

        // Last 5 match ratings (for form chart)
        List<Scorer> last5 = allScorers.size() > 5
                ? allScorers.subList(allScorers.size() - 5, allScorers.size()) : allScorers;
        profile.put("recentRatings", last5.stream()
                .map(s -> Math.round(s.getRating() * 10.0) / 10.0).toList());

        return profile;
    }

    // ==================== TEAM COMPETITION BREAKDOWN ====================

    /**
     * Per-(competition, season) team record (matches/W/D/L/goals), so League / Cup /
     * LoC / Stars Cup are never summed together the way the manager snapshot does.
     * Matches are read from the persisted competition results and only fall back
     * to Scorer rows for old/incomplete data. League position is
     * attached only on league-type lines (typeId 1 or 3): archived seasons use
     * CompetitionHistory.lastPosition, while the in-progress season uses the live standings.
     */
    public List<CompetitionStatLine> getTeamCompetitionBreakdown(long teamId) {
        List<Scorer> teamScorers = scorerRepository.findAllByTeamId(teamId);
        String teamName = teamRepository.findById(teamId).map(Team::getName).orElse("Unknown");

        // Final league positions are immutable season facts. New-season setup
        // snapshots them into CompetitionHistory before the live standings are
        // reset, so historical lines must read that snapshot instead of ranking
        // today's TeamCompetitionDetail table.
        Map<String, Integer> archivedLeaguePositions = competitionHistoryRepository
                .findByTeamId(teamId).stream()
                .filter(h -> h.getCompetitionTypeId() == 1 || h.getCompetitionTypeId() == 3)
                .filter(h -> h.getLastPosition() > 0)
                .collect(Collectors.toMap(
                        h -> h.getCompetitionId() + "|" + h.getSeasonNumber(),
                        h -> Math.toIntExact(h.getLastPosition()),
                        (first, duplicate) -> duplicate));

        // (competitionId|season) -> reconstructed unique matches -> [teamScore, opponentScore]
        Map<String, CompetitionStatLine> lines = new LinkedHashMap<>();
        Map<String, Map<String, int[]>> matchesByLine = new LinkedHashMap<>();
        Map<String, List<int[]>> officialMatchesByLine = new LinkedHashMap<>();
        Set<String> archivedKeys = new java.util.HashSet<>();
        Map<Long, Competition> competitionCache = new HashMap<>();

        // Seed completed seasons from their immutable snapshots. This also keeps
        // 0-0 cup appearances visible when no scorer row exists.
        for (CompetitionHistory history : competitionHistoryRepository.findByTeamId(teamId)) {
            String key = history.getCompetitionId() + "|" + history.getSeasonNumber();
            CompetitionStatLine line = new CompetitionStatLine(history.getCompetitionId(),
                    (int) history.getCompetitionTypeId(), history.getCompetitionName(),
                    (int) history.getSeasonNumber());
            line.setMatches(history.getGames());
            line.setWins(history.getWins());
            line.setDraws(history.getDraws());
            line.setLosses(history.getLoses());
            line.setGoalsFor(history.getGoalsFor());
            line.setGoalsAgainst(history.getGoalsAgainst());
            if (history.getCompetitionTypeId() == 1 || history.getCompetitionTypeId() == 3)
                line.setLeaguePosition((int) history.getLastPosition());
            lines.put(key, line);
            archivedKeys.add(key);
        }

        // Seed every current competition from entries, even before the first
        // match or after a scoreless match.
        long currentSeason = roundRepository.findById(1L).map(r -> r.getSeason()).orElse(1L);
        for (var entry : competitionTeamInfoRepository.findAllByTeamIdAndSeasonNumber(teamId, currentSeason)) {
            Competition competition = competitionCache.computeIfAbsent(entry.getCompetitionId(),
                    id -> competitionRepository.findById(id).orElse(null));
            if (competition == null) continue;
            String key = competition.getId() + "|" + currentSeason;
            lines.putIfAbsent(key, new CompetitionStatLine(competition.getId(), (int) competition.getTypeId(),
                    competition.getName(), (int) currentSeason));
        }

        // A scorer row represents a player's contribution, not a fixture. Some
        // European qualifiers (and all 0-0 matches) legitimately have no such
        // row. Persisted CompetitionTeamInfoDetail scores are therefore the
        // source of truth for the team record shown on My Manager.
        for (CompetitionTeamInfoDetail result : competitionTeamInfoDetailRepository.findAllByTeamId(teamId)) {
            int[] score = parseOfficialScore(result.getScore());
            if (score == null) continue;

            Competition competition = competitionCache.computeIfAbsent(result.getCompetitionId(),
                    id -> competitionRepository.findById(id).orElse(null));
            if (competition == null) continue;

            int teamScore = result.getTeam1Id() == teamId ? score[0] : score[1];
            int opponentScore = result.getTeam1Id() == teamId ? score[1] : score[0];
            String lineKey = result.getCompetitionId() + "|" + result.getSeasonNumber();
            lines.putIfAbsent(lineKey, new CompetitionStatLine(
                    competition.getId(), (int) competition.getTypeId(), competition.getName(),
                    (int) result.getSeasonNumber()));
            officialMatchesByLine.computeIfAbsent(lineKey, ignored -> new ArrayList<>())
                    .add(new int[]{teamScore, opponentScore});
        }

        String lastMatchKey = "";
        int matchCounter = 0;
        for (Scorer s : teamScorers) {
            // skip season-start placeholder rows (teamScore=-1, opponentTeamId=-1)
            if (s.getTeamScore() < 0 || s.getOpponentTeamId() < 0) continue;

            String lineKey = s.getCompetitionId() + "|" + s.getSeasonNumber();
            if (archivedKeys.contains(lineKey)) continue;
            lines.computeIfAbsent(lineKey, k -> new CompetitionStatLine(
                    s.getCompetitionId(), s.getCompetitionTypeId(), s.getCompetitionName(), s.getSeasonNumber()));

            String candidateKey = s.getCompetitionId() + "_" + s.getSeasonNumber() + "_"
                    + s.getOpponentTeamId() + "_" + s.getTeamScore() + "_" + s.getOpponentScore();
            if (!candidateKey.equals(lastMatchKey)) {
                matchCounter++;
                lastMatchKey = candidateKey;
            }
            String matchKey = candidateKey + "_" + matchCounter;
            matchesByLine.computeIfAbsent(lineKey, k -> new LinkedHashMap<>())
                    .putIfAbsent(matchKey, new int[]{s.getTeamScore(), s.getOpponentScore()});
        }

        for (Map.Entry<String, CompetitionStatLine> e : lines.entrySet()) {
            CompetitionStatLine line = e.getValue();
            line.setTeamId(teamId);
            line.setTeamName(teamName);
            List<int[]> officialMatches = officialMatchesByLine.get(e.getKey());
            if (officialMatches != null && !officialMatches.isEmpty()) {
                resetTeamRecord(line);
                for (int[] scores : officialMatches) applyTeamResult(line, scores);
            } else {
                for (int[] scores : matchesByLine.getOrDefault(e.getKey(), Map.of()).values()) {
                    applyTeamResult(line, scores);
                }
            }
            if (line.getCompetitionTypeId() == 1 || line.getCompetitionTypeId() == 3) {
                Integer archivedPosition = archivedLeaguePositions.get(e.getKey());
                line.setLeaguePosition(archivedPosition != null
                        ? archivedPosition
                        : computeLeaguePosition(teamId, line.getCompetitionId()));
            }
            applyCompetitionProgress(line, teamId);
        }

        return lines.values().stream()
                .sorted(Comparator.comparingInt(CompetitionStatLine::getSeasonNumber).reversed()
                        .thenComparingInt(CompetitionStatLine::getCompetitionTypeId))
                .toList();
    }

    private int[] parseOfficialScore(String score) {
        if (score == null || score.isBlank()) return null;
        Matcher matcher = RESULT_SCORE_PATTERN.matcher(score);
        if (!matcher.find()) return null;
        return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    private void resetTeamRecord(CompetitionStatLine line) {
        line.setMatches(0);
        line.setWins(0);
        line.setDraws(0);
        line.setLosses(0);
        line.setGoalsFor(0);
        line.setGoalsAgainst(0);
    }

    private void applyTeamResult(CompetitionStatLine line, int[] scores) {
        int teamScore = scores[0];
        int opponentScore = scores[1];
        line.setMatches(line.getMatches() + 1);
        line.setGoalsFor(line.getGoalsFor() + teamScore);
        line.setGoalsAgainst(line.getGoalsAgainst() + opponentScore);
        if (teamScore > opponentScore) line.setWins(line.getWins() + 1);
        else if (teamScore == opponentScore) line.setDraws(line.getDraws() + 1);
        else line.setLosses(line.getLosses() + 1);
    }

    private void applyCompetitionProgress(CompetitionStatLine line, long teamId) {
        Map<String, Object> progress = competitionProgressService.teamProgress(
                teamId, line.getCompetitionId(), line.getSeasonNumber());
        if (progress.isEmpty()) return;
        line.setEntryRound(numberAsLong(progress.get("entryRound")));
        line.setEntryStage((String) progress.get("entryStage"));
        line.setCurrentRound(numberAsLong(progress.get("currentRound")));
        line.setCurrentStage((String) progress.get("currentStage"));
        line.setStageReached((String) progress.get("stageReached"));
        line.setStatus((String) progress.get("status"));
        line.setStatusLabel((String) progress.get("statusLabel"));
        line.setEliminatedByTeamId(numberAsLong(progress.get("eliminatedByTeamId")));
        line.setEliminatedByTeamName((String) progress.get("eliminatedByTeamName"));
    }

    private Long numberAsLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /** Live standings position of a team in a competition (1-based). Returns null if not found. */
    private Integer computeLeaguePosition(long teamId, long competitionId) {
        List<TeamCompetitionDetail> standings = teamCompetitionDetailRepository.findAllByCompetitionId(competitionId).stream()
                .sorted((o1, o2) -> {
                    if (o1.getPoints() != o2.getPoints()) return o2.getPoints() - o1.getPoints();
                    if (o1.getGoalDifference() != o2.getGoalDifference()) return o2.getGoalDifference() - o1.getGoalDifference();
                    return o2.getGoalsFor() - o1.getGoalsFor();
                })
                .toList();
        int pos = 1;
        for (TeamCompetitionDetail d : standings) {
            if (d.getTeamId() == teamId) return pos;
            pos++;
        }
        return null;
    }

    // ==================== PLAYER COMPETITION BREAKDOWN ====================

    /**
     * All-competitions total PLUS per-competition and per-(competitionTypeId, season)
     * goals/assists/appearances for one player, derived entirely from Scorer rows.
     * Fixes the leaderboard gap where LoC (4) and Stars Cup (5) were dropped.
     */
    public Map<String, Object> getPlayerCompetitionBreakdown(long playerId) {
        List<Scorer> allScorers = scorerRepository.findAllByPlayerId(playerId).stream()
                .filter(s -> s.getTeamScore() >= 0 && s.getOpponentTeamId() >= 0) // drop placeholders
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("playerId", playerId);
        result.put("playerName", humanRepository.findById(playerId).map(Human::getName).orElse("Unknown"));

        // All-competitions total
        result.put("total", aggBlock(allScorers));

        // Per competition (one entry per distinct competitionId)
        Map<Long, List<Scorer>> byComp = allScorers.stream()
                .collect(Collectors.groupingBy(Scorer::getCompetitionId, LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> byCompetition = new ArrayList<>();
        for (Map.Entry<Long, List<Scorer>> e : byComp.entrySet()) {
            Scorer sample = e.getValue().get(0);
            Map<String, Object> row = aggBlock(e.getValue());
            row.put("competitionId", e.getKey());
            row.put("competitionTypeId", sample.getCompetitionTypeId());
            row.put("competitionName", sample.getCompetitionName());
            byCompetition.add(row);
        }
        result.put("byCompetition", byCompetition);

        // Per (competitionTypeId, season) — keeps LoC/Stars Cup separated across seasons
        String[] typeNames = {"", "League", "Cup", "Second League", "League of Champions", "Stars Cup"};
        Map<String, List<Scorer>> byTypeSeason = allScorers.stream()
                .collect(Collectors.groupingBy(s -> s.getCompetitionTypeId() + "|" + s.getSeasonNumber(),
                        LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> byTypeAndSeason = new ArrayList<>();
        for (Map.Entry<String, List<Scorer>> e : byTypeSeason.entrySet()) {
            Scorer sample = e.getValue().get(0);
            int typeId = sample.getCompetitionTypeId();
            Map<String, Object> row = aggBlock(e.getValue());
            row.put("competitionTypeId", typeId);
            row.put("competitionTypeName", typeId >= 0 && typeId < typeNames.length ? typeNames[typeId] : "Other");
            row.put("seasonNumber", sample.getSeasonNumber());
            byTypeAndSeason.add(row);
        }
        byTypeAndSeason.sort(Comparator
                .comparingInt((Map<String, Object> r) -> (int) r.get("seasonNumber")).reversed()
                .thenComparingInt(r -> (int) r.get("competitionTypeId")));
        result.put("byTypeAndSeason", byTypeAndSeason);

        return result;
    }

    /** goals/assists/appearances/avgRating aggregate for a set of scorer rows. */
    private Map<String, Object> aggBlock(List<Scorer> scorers) {
        Map<String, Object> block = new LinkedHashMap<>();
        int appearances = scorers.size();
        block.put("appearances", appearances);
        block.put("goals", scorers.stream().mapToInt(Scorer::getGoals).sum());
        block.put("assists", scorers.stream().mapToInt(Scorer::getAssists).sum());
        block.put("avgRating", appearances > 0
                ? Math.round(scorers.stream().mapToDouble(Scorer::getRating).average().orElse(0) * 100.0) / 100.0
                : 0.0);
        return block;
    }
}
