package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.FinancialRecord;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.FinancialRecordRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Read-only current-season comparisons. Crowd counts are observed matchday ledger values. */
@Service
public class CompetitionEconomyRankingService {
    private final CompetitionRepository competitions;
    private final CompetitionTeamInfoRepository entries;
    private final CompetitionTeamInfoDetailRepository results;
    private final FinancialRecordRepository finances;
    private final HumanRepository humans;
    private final TeamRepository teams;
    private final GameStateService gameState;

    public CompetitionEconomyRankingService(CompetitionRepository competitions,
                                            CompetitionTeamInfoRepository entries,
                                            CompetitionTeamInfoDetailRepository results,
                                            FinancialRecordRepository finances,
                                            HumanRepository humans,
                                            TeamRepository teams,
                                            GameStateService gameState) {
        this.competitions = competitions;
        this.entries = entries;
        this.results = results;
        this.finances = finances;
        this.humans = humans;
        this.teams = teams;
        this.gameState = gameState;
    }

    @Transactional(readOnly = true)
    public Ranking ranking() {
        int season = (int) gameState.currentSeason();
        List<Competition> allCompetitions = competitions.findAll();
        List<Team> allTeams = teams.findAll();
        Map<Long, Team> teamById = allTeams.stream().collect(Collectors.toMap(Team::getId, team -> team));
        Map<Long, Set<Long>> members = new HashMap<>();
        Map<String, List<Long>> competitionIdsByName = new HashMap<>();
        for (Competition competition : allCompetitions) {
            members.put(competition.getId(), new HashSet<>());
            competitionIdsByName.computeIfAbsent(competition.getName(), ignored -> new ArrayList<>())
                    .add(competition.getId());
        }
        // Entries include cup and continental clubs; results keep already-eliminated teams in scope.
        for (CompetitionTeamInfo entry : entries.findAllBySeasonNumber(season)) {
            members.computeIfAbsent(entry.getCompetitionId(), ignored -> new HashSet<>()).add(entry.getTeamId());
        }
        for (CompetitionTeamInfoDetail result : results.findAllBySeasonNumber(season)) {
            Set<Long> participants = members.computeIfAbsent(result.getCompetitionId(), ignored -> new HashSet<>());
            if (result.getTeam1Id() > 0) participants.add(result.getTeam1Id());
            if (result.getTeam2Id() > 0) participants.add(result.getTeam2Id());
        }
        for (Team team : allTeams) {
            Set<Long> primary = members.get(team.getCompetitionId());
            if (primary != null) primary.add(team.getId());
        }

        Map<Long, HumanRepository.TeamPlayerWages> wagesByTeam = humans.activePlayerWagesByTeam().stream()
                .collect(Collectors.toMap(HumanRepository.TeamPlayerWages::getTeamId, wages -> wages));
        Map<Long, Crowd> competitionCrowds = new HashMap<>();
        Map<Long, Crowd> clubCrowds = new HashMap<>();
        for (FinancialRecord record : finances.findAllBySeasonNumberAndCategory(season, "MATCH_DAY")) {
            ParsedCrowd parsed = parseCrowd(record.getDescription(), competitionIdsByName);
            if (parsed == null) continue;
            clubCrowds.computeIfAbsent(record.getTeamId(), ignored -> new Crowd()).add(parsed.attendance());
            List<Long> matchingIds = competitionIdsByName.get(parsed.competitionName());
            List<Long> eligibleIds = matchingIds.size() == 1 ? matchingIds : matchingIds.stream()
                    .filter(id -> members.getOrDefault(id, Set.of()).contains(record.getTeamId())).toList();
            Long competitionId = eligibleIds.size() == 1 ? eligibleIds.get(0) : null;
            if (competitionId != null) {
                competitionCrowds.computeIfAbsent(competitionId, ignored -> new Crowd()).add(parsed.attendance());
            }
        }

        List<CompetitionRow> competitionRows = new ArrayList<>();
        for (Competition competition : allCompetitions) {
            Set<Long> participantIds = members.getOrDefault(competition.getId(), Set.of());
            long playerCount = 0;
            long payroll = 0;
            long highestWage = 0;
            for (long teamId : participantIds) {
                HumanRepository.TeamPlayerWages wages = wagesByTeam.get(teamId);
                if (wages == null) continue;
                playerCount += wages.getPlayerCount();
                payroll += wages.getTotalWage();
                highestWage = Math.max(highestWage, wages.getTopWage());
            }
            Crowd crowd = competitionCrowds.getOrDefault(competition.getId(), new Crowd());
            competitionRows.add(new CompetitionRow(competition.getId(), competition.getName(), competition.getTypeId(),
                    participantIds.size(), crowd.matches, crowd.total, crowd.average(), playerCount, payroll,
                    playerCount == 0 ? 0 : Math.round((double) payroll / playerCount), highestWage));
        }
        competitionRows.sort(Comparator.comparingDouble(CompetitionRow::averageAttendance).reversed()
                .thenComparing(CompetitionRow::competitionName, String.CASE_INSENSITIVE_ORDER));

        List<ClubRow> clubRows = new ArrayList<>();
        for (Team team : allTeams) {
            HumanRepository.TeamPlayerWages wages = wagesByTeam.get(team.getId());
            Crowd crowd = clubCrowds.getOrDefault(team.getId(), new Crowd());
            long playerCount = wages == null ? 0 : wages.getPlayerCount();
            long payroll = wages == null ? 0 : wages.getTotalWage();
            clubRows.add(new ClubRow(team.getId(), team.getName(), team.getCompetitionId(),
                    crowd.matches, crowd.total, crowd.average(), playerCount, payroll,
                    playerCount == 0 ? 0 : Math.round((double) payroll / playerCount),
                    wages == null ? 0 : wages.getTopWage()));
        }
        clubRows.sort(Comparator.comparingLong(ClubRow::totalAttendance).reversed()
                .thenComparing(ClubRow::teamName, String.CASE_INSENSITIVE_ORDER));

        List<PlayerRow> playerRows = new ArrayList<>();
        int rank = 1;
        for (Human player : humans.findTop100ByTypeIdAndRetiredFalseAndTeamIdIsNotNullOrderByWageDescIdAsc(1)) {
            Team team = teamById.get(player.getTeamId());
            if (team == null) continue;
            playerRows.add(new PlayerRow(rank++, player.getId(), player.getName(), team.getId(), team.getName(),
                    team.getCompetitionId(), Math.max(0, player.getWage()), player.getPosition(), player.getAge()));
        }
        return new Ranking(season, competitionRows, clubRows, playerRows);
    }

    /** Loads the exact clubs and every currently contracted player for one competition. */
    @Transactional(readOnly = true)
    public CompetitionDetail detail(long competitionId) {
        Competition competition = competitions.findById(competitionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Competition not found"));
        int season = gameState.currentSeason();
        Set<Long> participantIds = new HashSet<>();
        for (CompetitionTeamInfo entry : entries.findAllByCompetitionIdAndSeasonNumber(competitionId, season)) {
            participantIds.add(entry.getTeamId());
        }
        for (CompetitionTeamInfoDetail result : results.findAllByCompetitionIdAndSeasonNumber(competitionId, season)) {
            if (result.getTeam1Id() > 0) participantIds.add(result.getTeam1Id());
            if (result.getTeam2Id() > 0) participantIds.add(result.getTeam2Id());
        }
        for (Team team : teams.findAllByCompetitionId(competitionId)) participantIds.add(team.getId());
        if (participantIds.isEmpty()) {
            return new CompetitionDetail(season, competitionId, competition.getName(), competition.getTypeId(),
                    List.of(), List.of());
        }

        Map<Long, Team> teamById = teams.findAllById(participantIds).stream()
                .collect(Collectors.toMap(Team::getId, team -> team));
        List<Human> players = new ArrayList<>(humans.findAllByTeamIdInAndTypeIdAndRetiredFalse(participantIds, 1));
        Map<Long, List<Human>> playersByTeam = players.stream().collect(Collectors.groupingBy(Human::getTeamId));
        Map<Long, Crowd> crowdsByTeam = new HashMap<>();
        Map<String, List<Long>> competitionName = Map.of(competition.getName(), List.of(competitionId));
        for (FinancialRecord record : finances.findAllBySeasonNumberAndCategory(season, "MATCH_DAY")) {
            if (!participantIds.contains(record.getTeamId())) continue;
            ParsedCrowd parsed = parseCrowd(record.getDescription(), competitionName);
            if (parsed == null) continue;
            crowdsByTeam.computeIfAbsent(record.getTeamId(), ignored -> new Crowd()).add(parsed.attendance());
        }

        List<ClubRow> clubRows = new ArrayList<>();
        for (long teamId : participantIds) {
            Team team = teamById.get(teamId);
            if (team == null) continue;
            List<Human> squad = playersByTeam.getOrDefault(teamId, List.of());
            long payroll = squad.stream().mapToLong(player -> Math.max(0, player.getWage())).sum();
            long topWage = squad.stream().mapToLong(Human::getWage).max().orElse(0);
            Crowd crowd = crowdsByTeam.getOrDefault(teamId, new Crowd());
            clubRows.add(new ClubRow(teamId, team.getName(), team.getCompetitionId(),
                    crowd.matches, crowd.total, crowd.average(), squad.size(), payroll,
                    squad.isEmpty() ? 0 : Math.round((double) payroll / squad.size()), topWage));
        }
        clubRows.sort(Comparator.comparingLong(ClubRow::totalAttendance).reversed()
                .thenComparing(ClubRow::teamName, String.CASE_INSENSITIVE_ORDER));

        players.sort(Comparator.comparingLong(Human::getWage).reversed()
                .thenComparing(Human::getName, String.CASE_INSENSITIVE_ORDER));
        List<PlayerRow> playerRows = new ArrayList<>();
        int rank = 1;
        for (Human player : players) {
            Team team = teamById.get(player.getTeamId());
            if (team == null) continue;
            playerRows.add(new PlayerRow(rank++, player.getId(), player.getName(), team.getId(), team.getName(),
                    team.getCompetitionId(), Math.max(0, player.getWage()), player.getPosition(), player.getAge()));
        }
        return new CompetitionDetail(season, competitionId, competition.getName(), competition.getTypeId(),
                clubRows, playerRows);
    }

    private ParsedCrowd parseCrowd(String description, Map<String, List<Long>> competitionIdsByName) {
        if (description == null || !description.startsWith("Match day vs ") || !description.endsWith(" fans")) return null;
        int marker = description.lastIndexOf(") - ");
        if (marker < 0) return null;
        long attendance;
        try {
            attendance = Long.parseLong(description.substring(marker + 4, description.length() - 5));
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (attendance < 0) return null;
        int start = description.indexOf(" (", "Match day vs ".length());
        while (start >= 0 && start < marker) {
            String name = description.substring(start + 2, marker);
            if (competitionIdsByName.containsKey(name)) return new ParsedCrowd(name, attendance);
            start = description.indexOf(" (", start + 2);
        }
        return null;
    }

    private static final class Crowd {
        long matches;
        long total;
        void add(long attendance) { matches++; total += attendance; }
        double average() { return matches == 0 ? 0 : Math.round((double) total * 10 / matches) / 10.0; }
    }

    private record ParsedCrowd(String competitionName, long attendance) {}

    public record CompetitionRow(long competitionId, String competitionName, long typeId, int clubs,
                                 long homeMatches, long totalAttendance, double averageAttendance,
                                 long players, long totalPlayerWages, long averagePlayerWage, long highestPlayerWage) {}
    public record ClubRow(long teamId, String teamName, long primaryCompetitionId,
                          long homeMatches, long totalAttendance, double averageAttendance,
                          long players, long totalPlayerWages, long averagePlayerWage, long highestPlayerWage) {}
    public record PlayerRow(int rank, long playerId, String playerName, long teamId, String teamName,
                            long primaryCompetitionId, long wage, String position, int age) {}
    public record Ranking(int season, List<CompetitionRow> competitions, List<ClubRow> clubs,
                          List<PlayerRow> players) {}
    public record CompetitionDetail(int season, long competitionId, String competitionName, long typeId,
                                    List<ClubRow> clubs, List<PlayerRow> players) {}
}
