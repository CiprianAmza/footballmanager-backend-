package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Goals in completed matches, grouped by competition for one season or the whole save. */
@Service
public class CompetitionGoalsRankingService {
    private final MatchStatsRepository matches;
    private final CompetitionRepository competitions;
    private final GameStateService gameState;

    public CompetitionGoalsRankingService(MatchStatsRepository matches,
                                          CompetitionRepository competitions,
                                          GameStateService gameState) {
        this.matches = matches;
        this.competitions = competitions;
        this.gameState = gameState;
    }

    @Transactional(readOnly = true)
    public Ranking ranking(Integer requestedSeason) {
        int currentSeason = (int) gameState.currentSeason();
        int season = requestedSeason == null ? currentSeason : requestedSeason;
        if (season < 0 || season > currentSeason) {
            throw new IllegalArgumentException("Season must be between 0 and " + currentSeason);
        }
        boolean allTime = season == 0;
        var totals = allTime ? matches.goalTotalsByCompetitionAllTime()
                : matches.goalTotalsByCompetitionForSeason(season);
        Map<Long, Competition> byId = competitions.findAllById(
                        totals.stream().map(MatchStatsRepository.CompetitionGoalTotals::getCompetitionId).toList())
                .stream().collect(Collectors.toMap(Competition::getId, Function.identity()));

        var rows = new ArrayList<Row>();
        for (var total : totals) {
            long played = total.getMatches();
            if (played == 0) continue;
            Competition competition = byId.get(total.getCompetitionId());
            if (competition == null) continue;
            long goals = total.getGoals();
            rows.add(new Row(0, competition.getId(), competition.getName(),
                    competition.getTypeId(), played, goals, (double) goals / played));
        }
        rows.sort(Comparator.comparingDouble(Row::goalsPerMatch).reversed()
                .thenComparing(Comparator.comparingLong(Row::matches).reversed())
                .thenComparing(Row::competitionName, String.CASE_INSENSITIVE_ORDER));
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            rows.set(index, new Row(index + 1, row.competitionId(), row.competitionName(),
                    row.typeId(), row.matches(), row.goals(), row.goalsPerMatch()));
        }
        return new Ranking(season, allTime, rows);
    }

    public record Row(int rank, long competitionId, String competitionName, long typeId,
                      long matches, long goals, double goalsPerMatch) {}

    public record Ranking(int season, boolean allTime, java.util.List<Row> rows) {}
}
