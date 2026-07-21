package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Historical scoring and assist records for one competition. Aggregation stays
 * in SQL so a century-long save returns only the requested leaders rather than
 * materialising every appearance in the JVM.
 */
@Service
public class CompetitionRecordService {

    private final ScorerRepository scorerRepository;
    private final HumanRepository humanRepository;
    private final CompetitionRepository competitionRepository;
    private final GameStateService gameStateService;

    public CompetitionRecordService(ScorerRepository scorerRepository,
                                    HumanRepository humanRepository,
                                    CompetitionRepository competitionRepository,
                                    GameStateService gameStateService) {
        this.scorerRepository = scorerRepository;
        this.humanRepository = humanRepository;
        this.competitionRepository = competitionRepository;
        this.gameStateService = gameStateService;
    }

    @Transactional(readOnly = true)
    public CompetitionRecords records(long competitionId, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        PageRequest page = PageRequest.of(0, limit);

        List<ScorerRepository.CompetitionSeasonRecordAggregate> seasonGoals =
                scorerRepository.findCompetitionSeasonGoalRecords(competitionId, page);
        List<ScorerRepository.CompetitionSeasonRecordAggregate> seasonAssists =
                scorerRepository.findCompetitionSeasonAssistRecords(competitionId, page);
        List<ScorerRepository.CompetitionAllTimeRecordAggregate> allTimeGoals =
                scorerRepository.findCompetitionAllTimeGoalRecords(competitionId, page);
        List<ScorerRepository.CompetitionAllTimeRecordAggregate> allTimeAssists =
                scorerRepository.findCompetitionAllTimeAssistRecords(competitionId, page);

        Map<Long, String> playerNames = loadPlayerNames(
                seasonGoals, seasonAssists, allTimeGoals, allTimeAssists);
        String competitionName = competitionRepository.findById(competitionId)
                .map(competition -> competition.getName())
                .orElse("Unknown competition");

        return new CompetitionRecords(
                competitionId,
                competitionName,
                Math.toIntExact(gameStateService.currentSeason()),
                limit,
                mapSeasonRows(seasonGoals, playerNames, Metric.GOALS),
                mapAllTimeRows(allTimeGoals, playerNames, Metric.GOALS),
                mapSeasonRows(seasonAssists, playerNames, Metric.ASSISTS),
                mapAllTimeRows(allTimeAssists, playerNames, Metric.ASSISTS));
    }

    private Map<Long, String> loadPlayerNames(
            List<ScorerRepository.CompetitionSeasonRecordAggregate> seasonGoals,
            List<ScorerRepository.CompetitionSeasonRecordAggregate> seasonAssists,
            List<ScorerRepository.CompetitionAllTimeRecordAggregate> allTimeGoals,
            List<ScorerRepository.CompetitionAllTimeRecordAggregate> allTimeAssists) {
        Set<Long> playerIds = new LinkedHashSet<>();
        seasonGoals.forEach(row -> playerIds.add(row.getPlayerId()));
        seasonAssists.forEach(row -> playerIds.add(row.getPlayerId()));
        allTimeGoals.forEach(row -> playerIds.add(row.getPlayerId()));
        allTimeAssists.forEach(row -> playerIds.add(row.getPlayerId()));
        Map<Long, String> names = new HashMap<>();
        for (Human player : humanRepository.findAllById(playerIds)) {
            names.put(player.getId(), player.getName());
        }
        return names;
    }

    private List<RecordRow> mapSeasonRows(
            List<ScorerRepository.CompetitionSeasonRecordAggregate> source,
            Map<Long, String> playerNames,
            Metric metric) {
        List<RecordRow> rows = new ArrayList<>(source.size());
        long previousValue = Long.MIN_VALUE;
        int previousRank = 0;
        for (int index = 0; index < source.size(); index++) {
            ScorerRepository.CompetitionSeasonRecordAggregate row = source.get(index);
            long value = metric.value(row.getGoals(), row.getAssists());
            int rank = value == previousValue ? previousRank : index + 1;
            Club club = club(row.getTeamCount(), row.getTeamId(), row.getTeamName());
            rows.add(new RecordRow(
                    rank,
                    row.getPlayerId(),
                    playerNames.getOrDefault(row.getPlayerId(), "Player #" + row.getPlayerId()),
                    club.teamId(),
                    club.teamName(),
                    club.multipleClubs(),
                    row.getSeasonNumber(),
                    row.getSeasonNumber(),
                    row.getSeasonNumber(),
                    safeLong(row.getAppearances()),
                    safeLong(row.getGoals()),
                    safeLong(row.getAssists()),
                    value));
            previousValue = value;
            previousRank = rank;
        }
        return rows;
    }

    private List<RecordRow> mapAllTimeRows(
            List<ScorerRepository.CompetitionAllTimeRecordAggregate> source,
            Map<Long, String> playerNames,
            Metric metric) {
        List<RecordRow> rows = new ArrayList<>(source.size());
        long previousValue = Long.MIN_VALUE;
        int previousRank = 0;
        for (int index = 0; index < source.size(); index++) {
            ScorerRepository.CompetitionAllTimeRecordAggregate row = source.get(index);
            long value = metric.value(row.getGoals(), row.getAssists());
            int rank = value == previousValue ? previousRank : index + 1;
            Club club = club(row.getTeamCount(), row.getTeamId(), row.getTeamName());
            rows.add(new RecordRow(
                    rank,
                    row.getPlayerId(),
                    playerNames.getOrDefault(row.getPlayerId(), "Player #" + row.getPlayerId()),
                    club.teamId(),
                    club.teamName(),
                    club.multipleClubs(),
                    null,
                    row.getFirstSeason(),
                    row.getLastSeason(),
                    safeLong(row.getAppearances()),
                    safeLong(row.getGoals()),
                    safeLong(row.getAssists()),
                    value));
            previousValue = value;
            previousRank = rank;
        }
        return rows;
    }

    private Club club(Long teamCount, Long teamId, String teamName) {
        if (safeLong(teamCount) > 1) return new Club(null, "Multiple clubs", true);
        String name = teamName == null || teamName.isBlank() ? "Unknown club" : teamName;
        return new Club(teamId == null || teamId <= 0 ? null : teamId, name, false);
    }

    private long safeLong(Long value) {
        return value == null ? 0 : value;
    }

    private enum Metric {
        GOALS {
            @Override long value(Long goals, Long assists) { return goals == null ? 0 : goals; }
        },
        ASSISTS {
            @Override long value(Long goals, Long assists) { return assists == null ? 0 : assists; }
        };

        abstract long value(Long goals, Long assists);
    }

    private record Club(Long teamId, String teamName, boolean multipleClubs) {}

    public record RecordRow(
            int rank,
            long playerId,
            String playerName,
            Long teamId,
            String teamName,
            boolean multipleClubs,
            Integer seasonNumber,
            Integer firstSeason,
            Integer lastSeason,
            long appearances,
            long goals,
            long assists,
            long recordValue) {}

    public record CompetitionRecords(
            long competitionId,
            String competitionName,
            int currentSeason,
            int limit,
            List<RecordRow> goalsSingleSeason,
            List<RecordRow> goalsAllTime,
            List<RecordRow> assistsSingleSeason,
            List<RecordRow> assistsAllTime) {}
}
