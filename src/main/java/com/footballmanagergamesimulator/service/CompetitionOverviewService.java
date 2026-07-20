package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/** Common overview data for leagues, cups, Super Cups and European competitions. */
@Service
public class CompetitionOverviewService {

    private final CompetitionRepository competitionRepository;
    private final CompetitionTeamInfoRepository entryRepository;
    private final CompetitionHistoryRepository historyRepository;
    private final CompetitionTeamInfoDetailRepository resultRepository;
    private final TeamRepository teamRepository;
    private final HumanRepository humanRepository;
    private final CompetitionProgressService progressService;

    public CompetitionOverviewService(CompetitionRepository competitionRepository,
                                      CompetitionTeamInfoRepository entryRepository,
                                      CompetitionHistoryRepository historyRepository,
                                      CompetitionTeamInfoDetailRepository resultRepository,
                                      TeamRepository teamRepository,
                                      HumanRepository humanRepository,
                                      CompetitionProgressService progressService) {
        this.competitionRepository = competitionRepository;
        this.entryRepository = entryRepository;
        this.historyRepository = historyRepository;
        this.resultRepository = resultRepository;
        this.teamRepository = teamRepository;
        this.humanRepository = humanRepository;
        this.progressService = progressService;
    }

    public Map<String, Object> overview(long competitionId, int season, Long selectedTeamId) {
        Competition competition = competitionRepository.findById(competitionId).orElse(null);
        if (competition == null) return Map.of();

        List<CompetitionTeamInfo> entries = entryRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, season);
        Set<Long> participantIds = entries.stream().map(CompetitionTeamInfo::getTeamId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (participantIds.isEmpty()) {
            historyRepository.findByCompetitionId(competitionId).stream()
                    .filter(h -> h.getSeasonNumber() == season)
                    .map(CompetitionHistory::getTeamId).forEach(participantIds::add);
        }

        Map<Long, Team> teams = teamRepository.findAllById(participantIds).stream()
                .collect(Collectors.toMap(Team::getId, team -> team));
        Map<Long, List<Human>> players = humanRepository
                .findAllByTeamIdInAndTypeId(participantIds, TypeNames.PLAYER_TYPE).stream()
                .filter(player -> !player.isRetired())
                .collect(Collectors.groupingBy(Human::getTeamId));
        Map<Long, Long> payrolls = humanRepository.findAll().stream()
                .filter(h -> h.getTeamId() != null && participantIds.contains(h.getTeamId()) && !h.isRetired())
                .collect(Collectors.groupingBy(Human::getTeamId, Collectors.summingLong(Human::getWage)));
        Map<Long, Long> entryRounds = entries.stream().collect(Collectors.toMap(
                CompetitionTeamInfo::getTeamId, CompetitionTeamInfo::getRound, Math::min));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (long teamId : participantIds) {
            Team team = teams.get(teamId);
            if (team == null) continue;
            List<Human> squad = players.getOrDefault(teamId, List.of());
            double topEleven = squad.stream().map(Human::getRating)
                    .sorted(Comparator.reverseOrder()).limit(11)
                    .mapToDouble(Double::doubleValue).average().orElse(0);
            long value = squad.stream().mapToLong(Human::getTransferValue).sum();
            long entryRound = entryRounds.getOrDefault(teamId, 1L);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("teamId", teamId);
            row.put("teamName", team.getName());
            row.put("color1", team.getColor1());
            row.put("color2", team.getColor2());
            row.put("monthlyPayroll", payrolls.getOrDefault(teamId, 0L));
            row.put("annualPayroll", payrolls.getOrDefault(teamId, 0L) * 12);
            row.put("squadValue", value);
            row.put("topElevenRating", round(topEleven));
            row.put("reputation", team.getReputation());
            row.put("entryRound", entryRound);
            row.put("entryStage", progressService.roundLabel(competitionId, entryRound, season));
            row.put("progress", progressService.teamProgress(teamId, competitionId, season));
            rows.add(row);
        }

        addRank(rows, "payrollRank", r -> ((Number) r.get("monthlyPayroll")).doubleValue());
        addRank(rows, "valueRank", r -> ((Number) r.get("squadValue")).doubleValue());
        addRank(rows, "ratingRank", r -> ((Number) r.get("topElevenRating")).doubleValue());
        addRank(rows, "reputationRank", r -> ((Number) r.get("reputation")).doubleValue());
        // The media prediction uses the same best-XI strength as match previews,
        // with reputation as a deterministic tie-breaker.
        List<Map<String, Object>> predicted = new ArrayList<>(rows);
        predicted.sort(Comparator
                .comparingDouble((Map<String, Object> r) -> ((Number) r.get("topElevenRating")).doubleValue()).reversed()
                .thenComparing(Comparator.comparingInt(
                        (Map<String, Object> r) -> ((Number) r.get("reputation")).intValue()).reversed()));
        for (int i = 0; i < predicted.size(); i++) predicted.get(i).put("mediaPrediction", i + 1);
        rows.sort(Comparator.comparingInt(r -> ((Number) r.get("mediaPrediction")).intValue()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("competitionId", competitionId);
        result.put("competitionName", competition.getName());
        result.put("competitionTypeId", competition.getTypeId());
        result.put("season", season);
        result.put("stages", progressService.stages(competitionId, season));
        result.putAll(finalSummary(competition, season));
        result.put("teams", rows);
        result.put("selectedTeamProgress", selectedTeamId == null || selectedTeamId <= 0
                || !participantIds.contains(selectedTeamId)
                ? null : progressService.teamProgress(selectedTeamId, competitionId, season));
        return result;
    }

    private Map<String, Object> finalSummary(Competition competition, int season) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (competition.getTypeId() == 1L || competition.getTypeId() == 3L) {
            List<CompetitionHistory> table = historyRepository.findByCompetitionId(competition.getId()).stream()
                    .filter(row -> row.getSeasonNumber() == season)
                    .sorted(Comparator.comparingLong(CompetitionHistory::getLastPosition))
                    .toList();
            summary.put("champion", table.isEmpty() ? null : teamSummary(table.get(0).getTeamId()));
            summary.put("runnerUp", table.size() < 2 ? null : teamSummary(table.get(1).getTeamId()));
            summary.put("finalResult", null);
            return summary;
        }

        List<CompetitionTeamInfoDetail> results = resultRepository
                .findAllByCompetitionIdAndSeasonNumber(competition.getId(), season);
        long expectedFinalRound = progressService.stages(competition.getId(), season).stream()
                .filter(stage -> "FINAL".equals(stage.get("phase")))
                .mapToLong(stage -> ((Number) stage.get("round")).longValue())
                .max()
                .orElseGet(() -> results.stream().mapToLong(CompetitionTeamInfoDetail::getRoundId).max().orElse(1));
        CompetitionTeamInfoDetail finalResult = results.stream()
                .filter(row -> row.getRoundId() == expectedFinalRound)
                .filter(row -> row.getWinnerTeamId() != null && !"FIRST_LEG".equals(row.getDecidedBy()))
                .max(Comparator.comparingInt(CompetitionTeamInfoDetail::getLegNumber)
                        .thenComparingLong(CompetitionTeamInfoDetail::getId))
                .orElse(null);

        if (finalResult == null) {
            summary.put("champion", null);
            summary.put("runnerUp", null);
            summary.put("finalResult", null);
            return summary;
        }

        long winnerId = finalResult.getWinnerTeamId();
        long runnerUpId = winnerId == finalResult.getTeam1Id()
                ? finalResult.getTeam2Id() : finalResult.getTeam1Id();
        summary.put("champion", teamSummary(winnerId));
        summary.put("runnerUp", teamSummary(runnerUpId));

        Map<String, Object> finalView = new LinkedHashMap<>();
        finalView.put("round", finalResult.getRoundId());
        finalView.put("matchIndex", finalResult.getMatchIndex());
        finalView.put("day", finalResult.getDay());
        finalView.put("legNumber", finalResult.getLegNumber());
        finalView.put("team1Id", finalResult.getTeam1Id());
        finalView.put("team2Id", finalResult.getTeam2Id());
        finalView.put("team1Name", finalResult.getTeamName1());
        finalView.put("team2Name", finalResult.getTeamName2());
        finalView.put("score", finalResult.getScore());
        finalView.put("winnerTeamId", winnerId);
        finalView.put("qualifiedTeamId", winnerId);
        finalView.put("decidedBy", finalResult.getDecidedBy());
        summary.put("finalResult", finalView);
        return summary;
    }

    private Map<String, Object> teamSummary(long teamId) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) return Map.of("teamId", teamId, "teamName", "Unknown");
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("teamId", teamId);
        view.put("teamName", team.getName());
        view.put("color1", team.getColor1());
        view.put("color2", team.getColor2());
        return view;
    }

    private void addRank(List<Map<String, Object>> rows, String field,
                         ToDoubleFunction<Map<String, Object>> value) {
        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingDouble(value).reversed());
        for (int i = 0; i < sorted.size(); i++) sorted.get(i).put(field, i + 1);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
