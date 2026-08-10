package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CompetitionCatalogService {

    private final CompetitionRepository competitionRepository;
    private final CompetitionTeamInfoRepository entryRepository;
    private final CompetitionTeamInfoDetailRepository resultRepository;
    private final CompetitionTeamInfoMatchRepository fixtureRepository;
    private final TeamCompetitionDetailRepository tableRepository;
    private final CompetitionHistoryRepository historyRepository;
    private final TeamRepository teamRepository;
    private final GameStateService gameStateService;
    private final CompetitionProgressService progressService;
    private final CompetitionMetadataService metadataService;

    public CompetitionCatalogService(CompetitionRepository competitionRepository,
                                     CompetitionTeamInfoRepository entryRepository,
                                     CompetitionTeamInfoDetailRepository resultRepository,
                                     CompetitionTeamInfoMatchRepository fixtureRepository,
                                     TeamCompetitionDetailRepository tableRepository,
                                     CompetitionHistoryRepository historyRepository,
                                     TeamRepository teamRepository,
                                     GameStateService gameStateService,
                                     CompetitionProgressService progressService,
                                     CompetitionMetadataService metadataService) {
        this.competitionRepository = competitionRepository;
        this.entryRepository = entryRepository;
        this.resultRepository = resultRepository;
        this.fixtureRepository = fixtureRepository;
        this.tableRepository = tableRepository;
        this.historyRepository = historyRepository;
        this.teamRepository = teamRepository;
        this.gameStateService = gameStateService;
        this.progressService = progressService;
        this.metadataService = metadataService;
    }

    public Map<String, Object> overview() {
        int season = (int) gameStateService.currentSeason();
        List<Competition> competitions = competitionRepository.findAll().stream()
                .sorted(Comparator.comparing(Competition::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<CompetitionTeamInfo> entries = entryRepository.findAllBySeasonNumber(season);
        List<CompetitionTeamInfoDetail> results = resultRepository.findAllBySeasonNumber(season);
        List<CompetitionTeamInfoMatch> fixtures = fixtureRepository.findAllBySeasonNumber(String.valueOf(season));
        List<TeamCompetitionDetail> tables = tableRepository.findAll();
        List<CompetitionHistory> history = historyRepository.findAllByCompetitionIdIn(
                competitions.stream().map(Competition::getId).toList());

        Map<Long, List<CompetitionTeamInfo>> entriesByCompetition = group(entries, CompetitionTeamInfo::getCompetitionId);
        Map<Long, List<CompetitionTeamInfoDetail>> resultsByCompetition = group(results, CompetitionTeamInfoDetail::getCompetitionId);
        Map<Long, List<CompetitionTeamInfoMatch>> fixturesByCompetition = group(fixtures, CompetitionTeamInfoMatch::getCompetitionId);
        Map<Long, List<TeamCompetitionDetail>> tablesByCompetition = group(tables, TeamCompetitionDetail::getCompetitionId);
        Map<Long, List<CompetitionHistory>> historyByCompetition = group(history, CompetitionHistory::getCompetitionId);
        Map<Long, Team> teams = teamRepository.findAll().stream()
                .collect(Collectors.toMap(Team::getId, Function.identity()));

        List<Map<String, Object>> rows = new ArrayList<>();
        int active = 0;
        int participants = 0;
        for (Competition competition : competitions) {
            Map<String, Object> row = catalogRow(competition, season,
                    entriesByCompetition.getOrDefault(competition.getId(), List.of()),
                    resultsByCompetition.getOrDefault(competition.getId(), List.of()),
                    fixturesByCompetition.getOrDefault(competition.getId(), List.of()),
                    tablesByCompetition.getOrDefault(competition.getId(), List.of()),
                    historyByCompetition.getOrDefault(competition.getId(), List.of()), teams);
            if ("IN_PROGRESS".equals(row.get("status"))) active++;
            participants += ((Number) row.get("participantCount")).intValue();
            rows.add(row);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("season", season);
        response.put("totalCompetitions", rows.size());
        response.put("domesticCompetitions", rows.stream().filter(row -> "Domestic".equals(row.get("scopeLabel"))).count());
        response.put("continentalCompetitions", rows.stream().filter(row -> "Continental".equals(row.get("scopeLabel"))).count());
        response.put("activeCompetitions", active);
        response.put("participantPlaces", participants);
        response.put("competitions", rows);
        return response;
    }

    private Map<String, Object> catalogRow(Competition competition, int season,
                                           List<CompetitionTeamInfo> entries,
                                           List<CompetitionTeamInfoDetail> results,
                                           List<CompetitionTeamInfoMatch> fixtures,
                                           List<TeamCompetitionDetail> table,
                                           List<CompetitionHistory> history,
                                           Map<Long, Team> teams) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("competitionId", competition.getId());
        row.put("name", competition.getName());
        row.put("nationId", competition.getNationId());
        row.put("typeId", competition.getTypeId());
        row.put("tier", competition.getTier());
        row.putAll(metadataService.metadata(competition));

        Set<Long> participantIds = entries.stream().map(CompetitionTeamInfo::getTeamId).collect(Collectors.toSet());
        if (participantIds.isEmpty() && competition.isLeague()) {
            participantIds = table.stream().map(TeamCompetitionDetail::getTeamId).collect(Collectors.toSet());
        }
        long currentRound = maxRound(entries, results, fixtures);
        int playedFixtures = (int) fixtures.stream().filter(match -> match.getTeam1Score() >= 0 && match.getTeam2Score() >= 0).count();
        int completedMatches = Math.max(results.size(), playedFixtures);
        int knownFixtures = Math.max(fixtures.size(), completedMatches);
        List<Map<String, Object>> stages = progressService.stages(competition.getId(), season);
        long finalRound = stages.stream().mapToLong(stage -> ((Number) stage.getOrDefault("round", 1)).longValue()).max().orElse(1);
        boolean hasFinalWinner = results.stream().anyMatch(result -> result.getRoundId() >= finalRound && result.getWinnerTeamId() != null);
        String status = completedMatches == 0 ? "NOT_STARTED" : hasFinalWinner ? "COMPLETED" : "IN_PROGRESS";

        row.put("participantCount", participantIds.size());
        row.put("currentRound", currentRound);
        row.put("currentStage", progressService.roundLabel(competition.getId(), Math.max(1, currentRound), season));
        row.put("stageCount", stages.size());
        row.put("completedMatches", completedMatches);
        row.put("knownFixtures", knownFixtures);
        row.put("progressPercent", progressPercent(status, currentRound, finalRound));
        row.put("status", status);
        row.put("statusLabel", switch (status) {
            case "COMPLETED" -> "Completed";
            case "IN_PROGRESS" -> "In progress";
            default -> "Not started";
        });
        row.put("goalsPerMatch", goalsPerMatch(results));

        TeamCompetitionDetail leader = table.stream().max(Comparator
                .comparingInt(TeamCompetitionDetail::getPoints)
                .thenComparingInt(TeamCompetitionDetail::getGoalDifference)
                .thenComparingInt(TeamCompetitionDetail::getGoalsFor)).orElse(null);
        row.put("leader", leader == null ? null : teamView(leader.getTeamId(), teams));
        CompetitionHistory holder = history.stream()
                .filter(item -> item.getSeasonNumber() < season && item.getLastPosition() == 1)
                .max(Comparator.comparingLong(CompetitionHistory::getSeasonNumber)).orElse(null);
        row.put("titleHolder", holder == null ? null : teamView(holder.getTeamId(), teams));
        row.put("titleHolderSeason", holder == null ? null : holder.getSeasonNumber());
        return row;
    }

    private long maxRound(List<CompetitionTeamInfo> entries, List<CompetitionTeamInfoDetail> results,
                          List<CompetitionTeamInfoMatch> fixtures) {
        long entryRound = entries.stream().mapToLong(CompetitionTeamInfo::getRound).max().orElse(1);
        long resultRound = results.stream().mapToLong(CompetitionTeamInfoDetail::getRoundId).max().orElse(1);
        long fixtureRound = fixtures.stream().filter(match -> match.getTeam1Score() >= 0)
                .mapToLong(CompetitionTeamInfoMatch::getRound).max().orElse(1);
        return Math.max(entryRound, Math.max(resultRound, fixtureRound));
    }

    private int progressPercent(String status, long currentRound, long finalRound) {
        if ("COMPLETED".equals(status)) return 100;
        if ("NOT_STARTED".equals(status)) return 0;
        return (int) Math.max(5, Math.min(95, Math.round(currentRound * 100.0 / Math.max(1, finalRound))));
    }

    private double goalsPerMatch(List<CompetitionTeamInfoDetail> results) {
        int goals = 0;
        int valid = 0;
        for (CompetitionTeamInfoDetail result : results) {
            if (result.getScore() == null) continue;
            String[] parts = result.getScore().trim().split("[-:]");
            if (parts.length != 2) continue;
            try {
                goals += Integer.parseInt(parts[0].trim()) + Integer.parseInt(parts[1].trim());
                valid++;
            } catch (NumberFormatException ignored) { }
        }
        return valid == 0 ? 0 : Math.round(goals * 100.0 / valid) / 100.0;
    }

    private Map<String, Object> teamView(long teamId, Map<Long, Team> teams) {
        Team team = teams.get(teamId);
        if (team == null) return Map.of("teamId", teamId, "teamName", "Unknown");
        return Map.of("teamId", teamId, "teamName", team.getName(),
                "color1", team.getColor1() == null ? "#65758b" : team.getColor1(),
                "color2", team.getColor2() == null ? "#2b3442" : team.getColor2());
    }

    private <T> Map<Long, List<T>> group(List<T> rows, Function<T, Long> key) {
        return rows.stream().collect(Collectors.groupingBy(key, HashMap::new, Collectors.toList()));
    }
}
