package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Captures immutable team-strength and financial values for competition history. */
@Service
public class CompetitionHistorySnapshotService {

    private final TeamRepository teamRepository;
    private final HumanRepository humanRepository;
    private final CompetitionTeamInfoRepository entryRepository;

    public CompetitionHistorySnapshotService(TeamRepository teamRepository,
                                             HumanRepository humanRepository,
                                             CompetitionTeamInfoRepository entryRepository) {
        this.teamRepository = teamRepository;
        this.humanRepository = humanRepository;
        this.entryRepository = entryRepository;
    }

    /**
     * Enriches all rows in one batch. Media prediction is calculated within
     * each competition from the captured Top XI and reputation, not from live
     * values when the history is opened later.
     */
    public void capture(List<CompetitionHistory> snapshots, long season) {
        if (snapshots.isEmpty()) return;

        Set<Long> teamIds = snapshots.stream().map(CompetitionHistory::getTeamId).collect(Collectors.toSet());
        Set<Long> competitionIds = snapshots.stream().map(CompetitionHistory::getCompetitionId)
                .collect(Collectors.toSet());
        Map<Long, Team> teams = teamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(Team::getId, Function.identity()));

        List<Human> people = humanRepository.findAllByTeamIdIn(teamIds).stream()
                .filter(person -> !person.isRetired())
                .toList();
        Map<Long, List<Human>> players = people.stream()
                .filter(person -> person.getTypeId() == TypeNames.PLAYER_TYPE)
                .collect(Collectors.groupingBy(Human::getTeamId));
        Map<Long, Long> payrolls = people.stream()
                .collect(Collectors.groupingBy(Human::getTeamId, Collectors.summingLong(Human::getWage)));

        Map<EntryKey, Long> entryRounds = new HashMap<>();
        for (CompetitionTeamInfo entry : entryRepository.findAllBySeasonNumber(season)) {
            if (!teamIds.contains(entry.getTeamId()) || !competitionIds.contains(entry.getCompetitionId())) continue;
            entryRounds.merge(new EntryKey(entry.getCompetitionId(), entry.getTeamId()), entry.getRound(), Math::min);
        }

        for (CompetitionHistory snapshot : snapshots) {
            List<Human> squad = players.getOrDefault(snapshot.getTeamId(), List.of());
            double topEleven = squad.stream().map(Human::getRating)
                    .sorted(Comparator.reverseOrder()).limit(11)
                    .mapToDouble(Double::doubleValue).average().orElse(0);
            long squadValue = squad.stream().mapToLong(Human::getTransferValue).sum();
            long monthlyPayroll = payrolls.getOrDefault(snapshot.getTeamId(), 0L);
            Team team = teams.get(snapshot.getTeamId());

            snapshot.setTopElevenRating(round(topEleven));
            snapshot.setSquadValue(squadValue);
            snapshot.setMonthlyPayroll(monthlyPayroll);
            snapshot.setAnnualPayroll(monthlyPayroll * 12);
            snapshot.setReputation(team == null ? 0 : team.getReputation());
            snapshot.setEntryRound(entryRounds.get(new EntryKey(
                    snapshot.getCompetitionId(), snapshot.getTeamId())));
            snapshot.setLandscapeSnapshotCaptured(true);
        }

        Map<Long, List<CompetitionHistory>> byCompetition = snapshots.stream()
                .collect(Collectors.groupingBy(CompetitionHistory::getCompetitionId));
        for (List<CompetitionHistory> competitionRows : byCompetition.values()) {
            List<CompetitionHistory> prediction = new ArrayList<>(competitionRows);
            prediction.sort(Comparator
                    .comparingDouble((CompetitionHistory row) -> row.getTopElevenRating()).reversed()
                    .thenComparing(Comparator.comparingInt(
                            (CompetitionHistory row) -> row.getReputation()).reversed())
                    .thenComparingLong(CompetitionHistory::getTeamId));
            for (int i = 0; i < prediction.size(); i++) prediction.get(i).setMediaPrediction(i + 1);
        }
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record EntryKey(long competitionId, long teamId) {}
}
