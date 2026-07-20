package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.List;

@RestController
@RequestMapping("/teams")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class TeamController {
    
    TeamRepository teamRepository;
    HumanRepository humanRepository;
    @Autowired
    ScoutRepository scoutRepository;
    @Autowired
    ClubCoefficientRepository clubCoefficientRepository;
    @Autowired
    TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired
    CompetitionRepository competitionRepository;
    @Autowired
    RoundRepository roundRepository;
    @Autowired
    GameCalendarRepository gameCalendarRepository;
    @Autowired
    InjuryRepository injuryRepository;
    @Autowired
    SuspensionRepository suspensionRepository;
    @Autowired
    com.footballmanagergamesimulator.service.FinanceService financeService;
    @Autowired
    com.footballmanagergamesimulator.service.InjuryTimelineService injuryTimelineService;
    @Autowired
    com.footballmanagergamesimulator.config.GameplayFeatureConfig gameplayFeatures;

    private static final int[] MONTH_START_DAYS = {1, 32, 62, 93, 123, 154, 185, 213, 244, 274, 305, 335};

    @Autowired
    public TeamController(TeamRepository teamRepository, HumanRepository humanRepository) {

        this.teamRepository = teamRepository;
        this.humanRepository = humanRepository;
    }

    @GetMapping("/getTeamNameById/{teamId}")
    public String getTeamNameByTeamId(@PathVariable(name = "teamId") long teamId) {

        return teamRepository.findNameById(teamId);
    }

    /**
     * Lightweight team metadata used by tactics/squad pages for branding
     * (colors, name, reputation). Returns null fields rather than 404 if a team
     * is missing, so the caller can fall back to defaults.
     */
    @GetMapping("/info/{teamId}")
    public Map<String, Object> getTeamInfo(@PathVariable(name = "teamId") long teamId) {
        Map<String, Object> result = new LinkedHashMap<>();
        teamRepository.findById(teamId).ifPresent(t -> {
            result.put("id", t.getId());
            result.put("name", t.getName());
            result.put("color1", t.getColor1());
            result.put("color2", t.getColor2());
            result.put("reputation", t.getReputation());
            result.put("stadiumName", t.getStadiumName());
        });
        return result;
    }

    @GetMapping("/all")
    public List<Map<String, Object>> getAllTeams() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Team team : teamRepository.findAll()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", team.getId());
            t.put("name", team.getName());
            result.add(t);
        }
        result.sort(Comparator.comparing(a -> (String) a.get("name")));
        return result;
    }
    
    @GetMapping("/allPlayers/{teamId}")
    public List<Human> getAllPlayersForSquadByTeamId(@PathVariable(name = "teamId") long teamId) {

        List<Human> allPlayers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);

        return allPlayers;
    }

    /**
     * One batched availability response for squad/tactics screens. It explains
     * why a player cannot be selected and for how long, without a request per
     * player. A player can have an injury plus competition-specific bans, so
     * the response deliberately contains one row per reason.
     */
    @GetMapping("/availability/{teamId}")
    public List<Map<String, Object>> getSquadAvailability(@PathVariable long teamId) {
        if (gameplayFeatures.isPlayerAvailabilityDisabled()) return List.of();
        Map<Long, Human> playersById = humanRepository
                .findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE)
                .stream()
                .collect(java.util.stream.Collectors.toMap(Human::getId, p -> p));
        Map<Long, String> competitionNames = competitionRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Competition::getId, Competition::getName));

        List<Map<String, Object>> result = new ArrayList<>();
        com.footballmanagergamesimulator.service.InjuryTimelineService.GameDate date = injuryTimelineService.currentDate();

        for (Injury injury : injuryRepository.findAllByTeamIdAndDaysRemainingGreaterThan(teamId, 0)) {
            int remainingDays = injuryTimelineService.remainingDays(injury, date.season(), date.day());
            if (remainingDays <= 0) continue;
            Human player = playersById.get(injury.getPlayerId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("playerId", injury.getPlayerId());
            row.put("playerName", player != null ? player.getName() : "Unknown player");
            row.put("type", "INJURY");
            row.put("label", injury.getInjuryType());
            row.put("severity", injury.getSeverity());
            row.put("remaining", remainingDays);
            row.put("remainingUnit", "days");
            row.put("competitionId", null);
            row.put("competitionName", "All competitions");
            row.put("explanation", injury.getInjuryType() + " (" + injury.getSeverity()
                    + ") — unavailable for approximately " + remainingDays + " more day(s).");
            result.add(row);
        }

        Map<String, List<Suspension>> groupedSuspensions = suspensionRepository
                .findAllByTeamIdAndActive(teamId, true).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        suspension -> suspension.getPlayerId() + ":"
                                + suspension.getCompetitionId() + ":" + suspension.getReason(),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        for (List<Suspension> suspensionGroup : groupedSuspensions.values()) {
            Suspension suspension = suspensionGroup.get(0);
            Human player = playersById.get(suspension.getPlayerId());
            boolean legacyDuplicates = suspensionGroup.size() > 1
                    && suspensionGroup.stream().allMatch(item -> item.getSourceMatchEventId() == 0);
            java.util.stream.IntStream remainingValues = suspensionGroup.stream()
                    .mapToInt(item -> Math.max(0, item.getMatchesBanned() - item.getMatchesServed()));
            // Old saves can contain the exact same ban several times because a
            // fast-forward round was reprocessed. Show that as one ban, not debt.
            int matchesRemaining = legacyDuplicates
                    ? remainingValues.max().orElse(0)
                    : remainingValues.sum();
            if (matchesRemaining == 0) continue;
            String competitionName = competitionNames.getOrDefault(
                    suspension.getCompetitionId(), "Competition " + suspension.getCompetitionId());
            String reason = switch (suspension.getReason()) {
                case "RED_CARD" -> "Direct red card";
                case "ACCUMULATED_YELLOWS" -> "Yellow-card accumulation";
                case "VIOLENT_CONDUCT" -> "Violent conduct";
                default -> suspension.getReason() == null
                        ? "Disciplinary suspension"
                        : suspension.getReason().replace('_', ' ');
            };

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("playerId", suspension.getPlayerId());
            row.put("playerName", player != null ? player.getName() : suspension.getPlayerName());
            row.put("type", "SUSPENSION");
            row.put("label", reason);
            row.put("severity", null);
            row.put("remaining", matchesRemaining);
            row.put("remainingUnit", matchesRemaining == 1 ? "match" : "matches");
            row.put("competitionId", suspension.getCompetitionId());
            row.put("competitionName", competitionName);
            row.put("explanation", reason + " — " + matchesRemaining + " match(es) remaining in " + competitionName + ".");
            result.add(row);
        }

        result.sort(Comparator
                .comparing((Map<String, Object> row) -> String.valueOf(row.get("playerName")))
                .thenComparing(row -> String.valueOf(row.get("type"))));
        return result;
    }

    @GetMapping("/finances/{teamId}")
    public Map<String, Object> getTeamFinances(@PathVariable(name = "teamId") long teamId) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) return Map.of();

        Round round = roundRepository.findById(1L).orElse(new Round());
        int currentSeason = (int) round.getSeason();

        Map<String, Object> finances = new LinkedHashMap<>();
        finances.put("transferBudget", team.getTransferBudget());
        finances.put("salaryBudget", team.getSalaryBudget());
        finances.put("totalFinances", team.getTotalFinances());

        // Calculate competition income for current season
        List<Competition> allComps = competitionRepository.findAll();
        List<TeamCompetitionDetail> allDetails = teamCompetitionDetailRepository.findAll();

        long leagueIncome = 0;
        long europeanIncome = 0;
        String leagueName = "";
        int leaguePosition = 0;

        for (Competition comp : allComps) {
            if (comp.getTypeId() != 1 && comp.getTypeId() != 3) continue;

            long leagueBase = (comp.getTypeId() == 1) ? 1_500_000L : 400_000L;

            List<TeamCompetitionDetail> standings = allDetails.stream()
                    .filter(d -> d.getCompetitionId() == comp.getId())
                    .sorted((a, b) -> {
                        if (a.getPoints() != b.getPoints()) return b.getPoints() - a.getPoints();
                        if (a.getGoalDifference() != b.getGoalDifference()) return b.getGoalDifference() - a.getGoalDifference();
                        return b.getGoalsFor() - a.getGoalsFor();
                    })
                    .toList();

            int numTeams = standings.size();
            if (numTeams == 0) continue;

            int position = 1;
            for (TeamCompetitionDetail detail : standings) {
                if (detail.getTeamId() == teamId) {
                    leagueIncome = (long) (leagueBase * (numTeams + 1 - position) / (numTeams / 2.0));
                    leagueName = comp.getName();
                    leaguePosition = position;
                    break;
                }
                position++;
            }
        }

        // European income
        Optional<ClubCoefficient> cc = clubCoefficientRepository.findByTeamIdAndSeasonNumber(teamId, currentSeason);
        if (cc.isEmpty()) {
            cc = clubCoefficientRepository.findByTeamIdAndSeasonNumber(teamId, currentSeason - 1);
        }
        europeanIncome = cc.map(c -> (long) (c.getPoints() * 200_000)).orElse(0L);

        // Calculate total monthly wages (players + staff + scouts)
        List<Human> teamMembers = humanRepository.findAllByTeamId(teamId);
        long totalWages = teamMembers.stream()
            .filter(h -> !h.isRetired())
            .mapToLong(Human::getWage)
            .sum();

        // Add scout wages
        List<Scout> scouts = scoutRepository.findAllByTeamId(teamId);
        long scoutWages = scouts.stream().mapToLong(Scout::getWage).sum();
        totalWages += scoutWages;

        // Calculate how many months have passed this season
        int monthsPassed = 0;
        List<GameCalendar> calendars = gameCalendarRepository.findBySeason(currentSeason);
        if (!calendars.isEmpty()) {
            int currentDay = calendars.get(0).getCurrentDay();
            for (int startDay : MONTH_START_DAYS) {
                if (currentDay >= startDay) monthsPassed++;
            }
        }
        long wagesPaidThisSeason = totalWages * monthsPassed;

        finances.put("monthlyWages", totalWages);
        finances.put("scoutWages", scoutWages);
        finances.put("monthsPassed", monthsPassed);
        finances.put("wagesPaidThisSeason", wagesPaidThisSeason);
        finances.put("leagueIncome", leagueIncome);
        finances.put("leagueName", leagueName);
        finances.put("leaguePosition", leaguePosition);
        finances.put("europeanIncome", europeanIncome);
        finances.put("estimatedSeasonIncome", leagueIncome + europeanIncome);

        // New finance system fields
        finances.put("debt", team.getDebt());
        finances.put("boardConfidence", team.getBoardConfidence());
        finances.put("transferBudgetPercentage", financeService.getTransferBudgetPercentage(team.getBoardConfidence()));
        finances.put("stadiumCapacity", team.getStadiumCapacity());

        return finances;
    }

    @GetMapping("/finances/report/{teamId}/{season}")
    public Map<String, Object> getFinancialReport(
            @PathVariable(name = "teamId") long teamId,
            @PathVariable(name = "season") int season) {
        return financeService.getFinancialReport(teamId, season);
    }

}
