package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Transfer;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TransferRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * Durable records calculated from match appearances rather than the current squad.
 * A transferred or retired player therefore remains in every table they earned.
 */
@Service
public class LegacyRecordsService {

    private final ScorerRepository scorers;
    private final HumanRepository humans;
    private final TeamRepository teams;
    private final CompetitionRepository competitions;
    private final CompetitionHistoryRepository competitionHistory;
    private final TransferRepository transfers;
    private final GameStateService gameState;

    public LegacyRecordsService(ScorerRepository scorers, HumanRepository humans,
                                TeamRepository teams, CompetitionRepository competitions,
                                CompetitionHistoryRepository competitionHistory,
                                TransferRepository transfers, GameStateService gameState) {
        this.scorers = scorers;
        this.humans = humans;
        this.teams = teams;
        this.competitions = competitions;
        this.competitionHistory = competitionHistory;
        this.transfers = transfers;
        this.gameState = gameState;
    }

    @Transactional(readOnly = true)
    public LegacyRecords club(long teamId, Integer requestedSeason, int requestedLimit) {
        return records(Scope.CLUB, teamId, requestedSeason, requestedLimit);
    }

    @Transactional(readOnly = true)
    public LegacyRecords competition(long competitionId, Integer requestedSeason, int requestedLimit) {
        return records(Scope.COMPETITION, competitionId, requestedSeason, requestedLimit);
    }

    @Transactional(readOnly = true)
    public LegacyRecords world(Integer requestedSeason, int requestedLimit) {
        return records(Scope.WORLD, null, requestedSeason, requestedLimit);
    }

    private LegacyRecords records(Scope scope, Long scopeId, Integer requestedSeason, int requestedLimit) {
        int limit = Math.max(5, Math.min(requestedLimit, 100));
        List<Integer> seasons = seasons(scope, scopeId);
        int currentSeason = Math.toIntExact(gameState.currentSeason());
        int selectedSeason = requestedSeason != null && seasons.contains(requestedSeason)
                ? requestedSeason : seasons.stream().findFirst().orElse(currentSeason);

        List<ScorerRepository.LegacyRecordAggregate> careers = aggregate(scope, scopeId, null);
        List<ScorerRepository.LegacyRecordAggregate> season = aggregate(scope, scopeId, selectedSeason);
        Set<Long> ids = new HashSet<>();
        careers.forEach(row -> ids.add(safe(row.getPlayerId())));
        season.forEach(row -> ids.add(safe(row.getPlayerId())));
        Map<Long, Human> players = new HashMap<>();
        humans.findAllById(ids).forEach(player -> players.put(player.getId(), player));

        Map<Long, Integer> trophies = trophyCounts(scope, scopeId);
        List<LegendRow> careerRows = map(careers, players, trophies);
        List<LegendRow> seasonRows = map(season, players, trophies);
        List<LegendRow> history = new ArrayList<>(careerRows);
        history.sort(Comparator.comparingLong(LegendRow::appearances).reversed()
                .thenComparing(Comparator.comparingLong(LegendRow::goals).reversed())
                .thenComparing(LegendRow::playerName));

        return new LegacyRecords(
                scope.name(), scopeId, scopeName(scope, scopeId), currentSeason, selectedSeason,
                seasons, limit,
                rank(careerRows, LegendRow::goals, limit),
                rank(careerRows, LegendRow::assists, limit),
                rank(careerRows, LegendRow::appearances, limit),
                rank(careerRows, row -> row.trophies(), limit),
                rank(seasonRows, LegendRow::goals, limit),
                rank(seasonRows, LegendRow::assists, limit),
                rank(seasonRows, LegendRow::appearances, limit),
                bestEleven(seasonRows), bestEleven(careerRows), history,
                recordSales(scope, scopeId, limit, careerRows));
    }

    private List<Integer> seasons(Scope scope, Long id) {
        return switch (scope) {
            case CLUB -> scorers.findClubLegacySeasons(id);
            case COMPETITION -> scorers.findCompetitionLegacySeasons(id);
            case WORLD -> scorers.findDistinctSeasonNumbersWithMatches().stream()
                    .sorted(Comparator.reverseOrder()).toList();
        };
    }

    private List<ScorerRepository.LegacyRecordAggregate> aggregate(Scope scope, Long id, Integer season) {
        if (season == null) {
            return switch (scope) {
                case CLUB -> scorers.aggregateClubLegacy(id);
                case COMPETITION -> scorers.aggregateCompetitionLegacy(id);
                case WORLD -> scorers.aggregateWorldLegacy();
            };
        }
        return switch (scope) {
            case CLUB -> scorers.aggregateClubLegacySeason(id, season);
            case COMPETITION -> scorers.aggregateCompetitionLegacySeason(id, season);
            case WORLD -> scorers.aggregateWorldLegacySeason(season);
        };
    }

    private Map<Long, Integer> trophyCounts(Scope scope, Long id) {
        Long teamId = scope == Scope.CLUB ? id : null;
        Long competitionId = scope == Scope.COMPETITION ? id : null;
        List<CompetitionHistory> winners = scope == Scope.CLUB
                ? competitionHistory.findByTeamId(id)
                : scope == Scope.COMPETITION
                    ? competitionHistory.findByCompetitionId(id)
                    : competitionHistory.findAll();
        Set<String> winningSeasons = new HashSet<>();
        winners.stream().filter(row -> row.getLastPosition() == 1).forEach(row ->
                winningSeasons.add(key(row.getTeamId(), row.getCompetitionId(), row.getSeasonNumber())));

        Map<Long, Set<String>> wonByPlayer = new HashMap<>();
        for (ScorerRepository.TrophyParticipationAggregate row
                : scorers.findTrophyParticipations(teamId, competitionId)) {
            String key = key(safe(row.getTeamId()), safe(row.getCompetitionId()), safeInt(row.getSeasonNumber()));
            if (winningSeasons.contains(key)) {
                wonByPlayer.computeIfAbsent(safe(row.getPlayerId()), ignored -> new HashSet<>()).add(key);
            }
        }
        Map<Long, Integer> result = new HashMap<>();
        wonByPlayer.forEach((playerId, wins) -> result.put(playerId, wins.size()));
        return result;
    }

    private String key(long teamId, long competitionId, long season) {
        return teamId + ":" + competitionId + ":" + season;
    }

    private List<LegendRow> map(List<ScorerRepository.LegacyRecordAggregate> source,
                                Map<Long, Human> players, Map<Long, Integer> trophies) {
        List<LegendRow> rows = new ArrayList<>(source.size());
        for (ScorerRepository.LegacyRecordAggregate row : source) {
            long playerId = safe(row.getPlayerId());
            Human player = players.get(playerId);
            long ratingCount = safe(row.getRatingCount());
            double averageRating = ratingCount == 0 ? 0 : safe(row.getRatingTotal()) / ratingCount;
            boolean multipleClubs = safe(row.getTeamCount()) > 1;
            rows.add(new LegendRow(0, playerId,
                    player == null ? "Player #" + playerId : player.getName(),
                    player == null || player.getPosition() == null ? "" : player.getPosition(),
                    multipleClubs ? null : safeNullableId(row.getTeamId()),
                    multipleClubs ? "Multiple clubs" : safeName(row.getTeamName(), "Unknown club"),
                    multipleClubs, safeInt(row.getFirstSeason()), safeInt(row.getLastSeason()),
                    safe(row.getAppearances()), safe(row.getGoals()), safe(row.getAssists()),
                    round(averageRating), trophies.getOrDefault(playerId, 0), 0));
        }
        return rows;
    }

    private List<LegendRow> rank(List<LegendRow> source, ToLongFunction<LegendRow> metric, int limit) {
        List<LegendRow> sorted = source.stream()
                .filter(row -> metric.applyAsLong(row) > 0)
                .sorted(Comparator.comparingLong(metric).reversed()
                        .thenComparing(Comparator.comparingLong(LegendRow::appearances).reversed())
                        .thenComparing(LegendRow::playerName))
                .limit(limit).toList();
        List<LegendRow> ranked = new ArrayList<>(sorted.size());
        long previous = Long.MIN_VALUE;
        int previousRank = 0;
        for (int index = 0; index < sorted.size(); index++) {
            LegendRow row = sorted.get(index);
            long value = metric.applyAsLong(row);
            int rank = value == previous ? previousRank : index + 1;
            ranked.add(row.withRankAndValue(rank, value));
            previous = value;
            previousRank = rank;
        }
        return ranked;
    }

    private List<BestElevenPlayer> bestEleven(List<LegendRow> candidates) {
        if (candidates.isEmpty()) return List.of();
        List<LegendRow> ordered = candidates.stream()
                .sorted(Comparator.comparingDouble(this::legacyScore).reversed())
                .toList();
        String[] slots = {"GK", "DR", "DC", "DC", "DL", "DM", "MC", "MC", "AMR", "AML", "ST"};
        Set<Long> used = new HashSet<>();
        List<BestElevenPlayer> result = new ArrayList<>();
        for (String slot : slots) {
            LegendRow chosen = ordered.stream()
                    .filter(row -> !used.contains(row.playerId()) && fits(row.position(), slot))
                    .findFirst().orElseGet(() -> ordered.stream()
                            .filter(row -> !used.contains(row.playerId())).findFirst().orElse(null));
            if (chosen == null) break;
            used.add(chosen.playerId());
            result.add(new BestElevenPlayer(slot, chosen, round(legacyScore(chosen))));
        }
        return result;
    }

    private boolean fits(String rawPosition, String slot) {
        String position = rawPosition == null ? "" : rawPosition.toUpperCase(Locale.ROOT);
        if (slot.equals("GK")) return position.contains("GK");
        if (slot.equals("DC")) return position.contains("DC") || position.equals("CB");
        if (slot.equals("DR")) return position.contains("DR") || position.equals("RB");
        if (slot.equals("DL")) return position.contains("DL") || position.equals("LB");
        if (slot.equals("DM")) return position.contains("DM");
        if (slot.equals("MC")) return position.contains("MC") || position.equals("CM");
        if (slot.equals("AMR")) return position.contains("AMR") || position.equals("RW");
        if (slot.equals("AML")) return position.contains("AML") || position.equals("LW");
        return position.contains("ST") || position.contains("CF") || position.equals("FW");
    }

    private double legacyScore(LegendRow row) {
        return row.averageRating() * 10 + Math.sqrt(row.appearances()) * 3
                + row.goals() * .4 + row.assists() * .35 + row.trophies() * 2;
    }

    private List<TransferRecord> recordSales(Scope scope, Long id, int limit, List<LegendRow> careerRows) {
        List<Transfer> rows = scope == Scope.CLUB
                ? transfers.findRecordSalesByTeam(id, PageRequest.of(0, limit))
                : scope == Scope.WORLD
                    ? transfers.findWorldRecordSales(PageRequest.of(0, limit))
                    : careerRows.isEmpty() ? List.of() : transfers.findRecordSalesForPlayers(
                            careerRows.stream().map(LegendRow::playerId).toList(), PageRequest.of(0, limit));
        return rows.stream().map(row -> new TransferRecord(row.getPlayerId(), row.getPlayerName(),
                row.getSellTeamId(), row.getSellTeamName(), row.getBuyTeamId(), row.getBuyTeamName(),
                row.getPlayerTransferValue(), Math.toIntExact(row.getSeasonNumber()))).toList();
    }

    private String scopeName(Scope scope, Long id) {
        return switch (scope) {
            case CLUB -> teams.findById(id).map(team -> team.getName()).orElse("Club #" + id);
            case COMPETITION -> competitions.findById(id).map(comp -> comp.getName())
                    .orElse("Competition #" + id);
            case WORLD -> "World football";
        };
    }

    private long safe(Long value) { return value == null ? 0 : value; }
    private double safe(Double value) { return value == null ? 0 : value; }
    private int safeInt(Integer value) { return value == null ? 0 : value; }
    private Long safeNullableId(Long value) { return value == null || value <= 0 ? null : value; }
    private String safeName(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private double round(double value) { return Math.round(value * 100.0) / 100.0; }

    private enum Scope { CLUB, COMPETITION, WORLD }

    public record LegendRow(int rank, long playerId, String playerName, String position,
                            Long teamId, String teamName, boolean multipleClubs,
                            int firstSeason, int lastSeason, long appearances, long goals,
                            long assists, double averageRating, int trophies, long recordValue) {
        LegendRow withRankAndValue(int newRank, long value) {
            return new LegendRow(newRank, playerId, playerName, position, teamId, teamName,
                    multipleClubs, firstSeason, lastSeason, appearances, goals, assists,
                    averageRating, trophies, value);
        }
    }

    public record BestElevenPlayer(String slot, LegendRow player, double legacyScore) {}
    public record TransferRecord(long playerId, String playerName, long fromTeamId, String fromTeamName,
                                 long toTeamId, String toTeamName, long fee, int seasonNumber) {}
    public record LegacyRecords(String scopeType, Long scopeId, String scopeName, int currentSeason,
                                int selectedSeason, List<Integer> availableSeasons, int limit,
                                List<LegendRow> allTimeScorers, List<LegendRow> allTimeAssists,
                                List<LegendRow> allTimeAppearances, List<LegendRow> trophyLeaders,
                                List<LegendRow> seasonScorers, List<LegendRow> seasonAssists,
                                List<LegendRow> seasonAppearances,
                                List<BestElevenPlayer> seasonBestEleven,
                                List<BestElevenPlayer> allTimeBestEleven,
                                List<LegendRow> playerHistory, List<TransferRecord> recordSales) {}
}
