package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.*;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.user.UserRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/game")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class GameController {

    @Autowired
    UserContext userContext;

    @Autowired
    GameAdvanceService gameAdvanceService;

    @Autowired
    CalendarService calendarService;

    @Autowired
    GameCalendarRepository gameCalendarRepository;

    @Autowired
    PressConferenceService pressConferenceService;

    @Autowired
    YouthAcademyService youthAcademyService;

    @Autowired
    SponsorshipService sponsorshipService;

    @Autowired
    BoardRequestService boardRequestService;

    @Autowired
    FacilityUpgradeService facilityUpgradeService;

    @Autowired
    AwardService awardService;

    @Autowired
    TeamRepository teamRepository;

    @Autowired
    CompetitionRepository competitionRepository;

    @Autowired
    CompetitionTeamInfoRepository competitionTeamInfoRepository;

    @Autowired
    RoundRepository roundRepository;

    @Autowired
    HumanRepository humanRepository;

    @Autowired
    JobOfferService jobOfferService;

    @Autowired
    CompetitionHistoryRepository competitionHistoryRepository;

    @Autowired
    TransferRepository transferRepository;

    @Autowired
    SeasonObjectiveRepository seasonObjectiveRepository;

    @Autowired
    ManagerHistoryRepository managerHistoryRepository;

    @Autowired
    ScorerLeaderboardRepository scorerLeaderboardRepository;

    @Autowired
    ManagerInboxRepository managerInboxRepository;

    @Autowired
    TeamCompetitionDetailRepository teamCompetitionDetailRepository;

    @Autowired
    ScorerRepository scorerRepository;

    // Save/Load repositories
    @Autowired CalendarEventRepository calendarEventRepository;
    @Autowired PlayerSkillsRepository playerSkillsRepository;
    @Autowired InjuryRepository injuryRepository;
    @Autowired SuspensionRepository suspensionRepository;
    @Autowired LoanRepository loanRepository;
    @Autowired AdminPlayerMovementRepository adminPlayerMovementRepository;
    @Autowired TransferOfferRepository transferOfferRepository;
    @Autowired PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired TrainingScheduleRepository trainingScheduleRepository;
    @Autowired SponsorshipRepository sponsorshipRepository;
    @Autowired BoardRequestRepository boardRequestRepository;
    @Autowired FacilityUpgradeRepository facilityUpgradeRepository;
    @Autowired MatchEventRepository matchEventRepository;
    @Autowired CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    @Autowired CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired TeamPlayerHistoricalRelationRepository teamPlayerHistoricalRelationRepository;
    @Autowired TeamFacilitiesRepository teamFacilitiesRepository;
    @Autowired ClubCoefficientRepository clubCoefficientRepository;
    @Autowired PressConferenceRepository pressConferenceRepository;
    @Autowired NationalTeamCallupRepository nationalTeamCallupRepository;
    @Autowired YouthPlayerRepository youthPlayerRepository;
    @Autowired PlayerInteractionRepository playerInteractionRepository;
    @Autowired AwardRepository awardRepository;
    @Autowired AwardOverrideRepository awardOverrideRepository;
    @Autowired UserRepository userRepository;
    @Autowired StadiumRepository stadiumRepository;
    @Autowired FinancialRecordRepository financialRecordRepository;
    @Autowired MatchStatsRepository matchStatsRepository;
    @Autowired PlayerSeasonStatRepository playerSeasonStatRepository;
    @Autowired com.footballmanagergamesimulator.config.GameplayFeatureConfig gameplayFeatures;
    @Autowired GameStateService gameStateService;
    @Autowired GameLock gameLock;
    @Autowired FastForwardService fastForwardService;
    @Autowired GameSaveImportService gameSaveImportService;

    private static final int CURRENT_SAVE_VERSION = GameSaveImportService.CURRENT_SAVE_VERSION;

    // ==================== GAME SETUP ====================

    /**
     * Check if game setup has been completed (team selected).
     */
    @GetMapping("/isSetupComplete")
    public Map<String, Object> isSetupComplete(@RequestParam(required = false) Integer userId) {
        Map<String, Object> result = new LinkedHashMap<>();

        // If userId is provided, check user-specific team association first
        if (userId != null) {
            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                if (user.getTeamId() != null && user.getTeamId() > 0) {
                    result.put("setupComplete", true);
                    result.put("humanTeamId", user.getTeamId());

                    // Get manager name from Round (read-only, no sync to avoid multi-user race conditions)
                    List<Round> rounds = roundRepository.findAll();
                    if (!rounds.isEmpty()) {
                        result.put("managerName", rounds.get(0).getManagerName());
                    }
                    return result;
                } else {
                    // User exists but has no team. Three sub-cases:
                    //   1) Fresh user, never went through setup → setupComplete=false (show team picker)
                    //   2) Free agent (signed up without a team, never managed) → setupComplete=true + freeAgent flag
                    //   3) Fired veteran (was managing, got sacked) → setupComplete=false + managerFired (legacy UI flow)
                    if (user.isFired() && user.getManagerId() != null && !user.isEverManaged()) {
                        // Free agent path — surface as completed setup with the freeAgent flag.
                        result.put("setupComplete", true);
                        result.put("freeAgent", true);
                        result.put("managerFired", true); // reuses fired-state FE UI (job-search menu)
                        List<Round> rounds = roundRepository.findAll();
                        if (!rounds.isEmpty()) {
                            result.put("managerName", rounds.get(0).getManagerName());
                        }
                        return result;
                    }
                    result.put("setupComplete", false);
                    if (user.isFired()) {
                        result.put("managerFired", true);
                    }
                    return result;
                }
            }
            // userId was sent but the user row doesn't exist (DB wipe / stale localStorage).
            // Tell the frontend explicitly so it can purge its cached login and re-prompt.
            result.put("setupComplete", false);
            result.put("userNotFound", true);
            return result;
        }

        // Fallback: check Round-based setup (backward compatible)
        List<Round> rounds = roundRepository.findAll();
        if (rounds.isEmpty()) {
            result.put("setupComplete", false);
            return result;
        }
        Round round = rounds.get(0);
        result.put("setupComplete", round.getHumanTeamId() > 0);
        if (round.getHumanTeamId() > 0) {
            result.put("humanTeamId", round.getHumanTeamId());
            result.put("managerName", round.getManagerName());
        }
        return result;
    }

    /**
     * Returns all teams grouped by competition for the team selection screen.
     */
    @GetMapping("/availableTeams")
    public List<Map<String, Object>> getAvailableTeams() {
        List<Competition> competitions = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 1 || c.getTypeId() == 3) // leagues only
                .sorted(Comparator.comparingLong(Competition::getId))
                .toList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Competition comp : competitions) {
            Map<String, Object> league = new LinkedHashMap<>();
            league.put("competitionId", comp.getId());
            league.put("competitionName", comp.getName());

            List<Map<String, Object>> teams = teamRepository.findAll().stream()
                    .filter(t -> t.getCompetitionId() == comp.getId())
                    .sorted(Comparator.comparingInt(Team::getReputation).reversed())
                    .map(t -> {
                        Map<String, Object> teamInfo = new LinkedHashMap<>();
                        teamInfo.put("teamId", t.getId());
                        teamInfo.put("teamName", t.getName());
                        teamInfo.put("reputation", t.getReputation());
                        teamInfo.put("color1", t.getColor1());
                        teamInfo.put("color2", t.getColor2());
                        return teamInfo;
                    })
                    .toList();

            league.put("teams", teams);
            result.add(league);
        }
        return result;
    }

    /**
     * Complete the game setup: set manager details and select a team.
     * This sets the human team ID for the entire game.
     */
    @PostMapping("/setup")
    public Map<String, Object> setupGame(@RequestBody Map<String, Object> body) {
        String managerName = (String) body.get("managerName");
        Integer managerAge = body.get("managerAge") != null ? ((Number) body.get("managerAge")).intValue() : 35;
        Long selectedTeamId = body.get("teamId") != null ? ((Number) body.get("teamId")).longValue() : null;
        boolean freeAgent = Boolean.TRUE.equals(body.get("freeAgent")) || selectedTeamId == null || selectedTeamId <= 0;

        Map<String, Object> result = new LinkedHashMap<>();

        if (managerName == null || managerName.isBlank()) {
            result.put("success", false);
            result.put("error", "Manager name is required");
            return result;
        }

        Integer userId = body.get("userId") != null ? ((Number) body.get("userId")).intValue() : null;

        if (freeAgent) {
            // ============== Free agent path ==============
            // No team selected. We create a Human manager attached to no team (teamId=0L)
            // and mark the user as "fired" so the existing job-search UI lights up.
            Human freeAgentManager = new Human();
            freeAgentManager.setName(managerName);
            freeAgentManager.setAge(managerAge);
            freeAgentManager.setTeamId(0L);
            freeAgentManager.setTypeId(TypeNames.MANAGER_TYPE);
            freeAgentManager.setManagerReputation(500); // starter reputation
            humanRepository.save(freeAgentManager);

            // Round stays untouched (humanTeamId = 0). We persist the manager name/age
            // so any Round-based fallback still has it.
            List<Round> rounds = roundRepository.findAll();
            Round round = rounds.isEmpty() ? new Round() : rounds.get(0);
            if (round.getManagerName() == null || round.getManagerName().isBlank()) {
                round.setManagerName(managerName);
                round.setManagerAge(managerAge);
                roundRepository.save(round);
            }

            if (userId != null) {
                Optional<User> userOpt = userRepository.findById(userId);
                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    user.setTeamId(null);
                    user.setLastTeamId(null);
                    user.setManagerId(freeAgentManager.getId());
                    user.setFired(true);          // reuses fired-state UI → shows job-search
                    user.setEverManaged(false);   // distinguishes from "fired veteran"
                    user.setInitialOffersGenerated(false);
                    userRepository.save(user);

                    // Spawn welcome batch of 3 offers immediately so the inbox isn't empty.
                    try {
                        jobOfferService.generateInitialFreeAgentOffers(user.getId(), 3);
                        user.setInitialOffersGenerated(true);
                        userRepository.save(user);
                    } catch (Exception ex) {
                        System.err.println("Failed to seed initial free-agent offers: " + ex);
                    }
                }
            }

            result.put("success", true);
            result.put("freeAgent", true);
            result.put("managerName", managerName);
            return result;
        }

        // ============== Standard team-selection path ==============
        Team selectedTeam = teamRepository.findById(selectedTeamId).orElse(null);
        if (selectedTeam == null) {
            result.put("success", false);
            result.put("error", "Team not found");
            return result;
        }

        // Save setup to Round
        List<Round> rounds = roundRepository.findAll();
        Round round = rounds.isEmpty() ? new Round() : rounds.get(0);
        round.setHumanTeamId(selectedTeamId);
        round.setManagerName(managerName);
        round.setManagerAge(managerAge);
        roundRepository.save(round);

        // Update the human manager entity to match the selected team
        List<Human> managers = humanRepository.findAllByTeamIdAndTypeId(selectedTeamId, TypeNames.MANAGER_TYPE);
        long managerId = 0;
        if (!managers.isEmpty()) {
            Human existingManager = managers.get(0);
            existingManager.setName(managerName);
            existingManager.setAge(managerAge);
            humanRepository.save(existingManager);
            managerId = existingManager.getId();
        }

        // Persist user-team association if userId is provided
        if (userId != null) {
            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                user.setTeamId(selectedTeamId);
                user.setLastTeamId(selectedTeamId);
                user.setManagerId(managerId > 0 ? managerId : null);
                user.setFired(false);
                user.setEverManaged(true);
                userRepository.save(user);
            }
        }

        result.put("success", true);
        result.put("humanTeamId", selectedTeamId);
        result.put("teamName", selectedTeam.getName());
        result.put("managerName", managerName);
        return result;
    }

    @PostMapping("/advance")
    public Map<String, Object> advance() {
        int season = getCurrentSeason();
        return gameAdvanceService.advance(season);
    }

    @GetMapping("/state")
    public Map<String, Object> getState() {
        int season = getCurrentSeason();
        return gameAdvanceService.getGameState(season);
    }

    @PostMapping("/advanceToDay/{day}")
    public Map<String, Object> advanceToDay(@PathVariable int day) {
        int season = getCurrentSeason();
        return gameAdvanceService.advanceToDay(season, day);
    }

    @PostMapping("/unpause")
    public Map<String, Object> unpause() {
        int season = getCurrentSeason();
        GameCalendar cal = calendarService.getOrCreateCalendar(season);
        cal.setPaused(false);
        gameCalendarRepository.save(cal);
        return gameAdvanceService.advance(season);
    }

    // ==================== PRESS CONFERENCE ====================

    @PostMapping("/pressConference/{id}/respond")
    public Map<String, Object> respondToPressConference(
            @PathVariable long id,
            @RequestBody Map<String, String> body) {
        String responseType = body.get("responseType");
        Map<String, Object> pressResult = pressConferenceService.respondToPressConference(id, responseType);

        // Auto-unpause and continue advancing after press conference response
        int season = getCurrentSeason();
        GameCalendar cal = calendarService.getOrCreateCalendar(season);
        cal.setPaused(false);
        gameCalendarRepository.save(cal);

        // Include updated game state so frontend can refresh
        Map<String, Object> gameState = gameAdvanceService.getGameState(season);
        pressResult.put("gameState", gameState);
        return pressResult;
    }

    // ==================== YOUTH ACADEMY ====================

    @GetMapping("/youthAcademy/{teamId}")
    public List<YouthPlayer> getYouthAcademy(@PathVariable long teamId) {
        return youthAcademyService.getYouthSquad(teamId);
    }

    @PostMapping("/youthAcademy/promote/{youthPlayerId}")
    public Human promoteYouthPlayer(@PathVariable long youthPlayerId, HttpServletRequest request) {
        return youthAcademyService.promoteToFirstTeam(youthPlayerId, userContext.getTeamId(request));
    }

    // ==================== SPONSORSHIPS ====================

    @GetMapping("/sponsorships/{teamId}")
    public Map<String, Object> getSponsorships(@PathVariable long teamId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", sponsorshipService.getActiveSponsors(teamId));
        result.put("offered", sponsorshipService.getOfferedSponsors(teamId));
        return result;
    }

    @PostMapping("/sponsorship/{id}/accept")
    public Sponsorship acceptSponsorship(@PathVariable long id) {
        return sponsorshipService.acceptSponsorship(id);
    }

    @PostMapping("/sponsorship/{id}/reject")
    public Sponsorship rejectSponsorship(@PathVariable long id) {
        return sponsorshipService.rejectSponsorship(id);
    }

    // ==================== BOARD REQUESTS ====================

    @GetMapping("/boardRequests/{teamId}")
    public List<BoardRequest> getBoardRequests(@PathVariable long teamId) {
        int season = getCurrentSeason();
        return boardRequestService.getBoardRequests(teamId, season);
    }

    // ==================== FACILITIES ====================

    @GetMapping("/facilities/{teamId}")
    public Map<String, Object> getFacilities(@PathVariable long teamId) {
        // Lazy completion check: ensure any ready upgrades are completed before returning overview
        int season = getCurrentSeason();
        java.util.List<com.footballmanagergamesimulator.model.GameCalendar> calendars =
                gameCalendarRepository.findBySeason(season);
        if (!calendars.isEmpty()) {
            int currentDay = calendars.get(0).getCurrentDay();
            facilityUpgradeService.checkUpgradeCompletion(teamId, currentDay, season);
        }
        return facilityUpgradeService.getFullFacilityOverview(teamId);
    }

    @PostMapping("/facilities/upgrade")
    public FacilityUpgrade startFacilityUpgrade(@RequestBody Map<String, Object> body) {
        long teamId = ((Number) body.get("teamId")).longValue();
        String facilityType = (String) body.get("facilityType");
        int season = getCurrentSeason();
        // Get current day from calendar
        int currentDay = 0;
        java.util.List<com.footballmanagergamesimulator.model.GameCalendar> calendars =
                gameCalendarRepository.findBySeason(season);
        if (!calendars.isEmpty()) {
            currentDay = calendars.get(0).getCurrentDay();
        }
        return facilityUpgradeService.startUpgrade(teamId, facilityType, season, currentDay);
    }

    // ==================== AWARDS ====================

    @GetMapping("/awards/{season}")
    public List<Award> getAwards(@PathVariable int season) {
        return awardService.getAwardsForSeason(season);
    }

    // ==================== SEASON SUMMARY (Feature 8) ====================

    @GetMapping("/seasonSummary/{season}")
    public Map<String, Object> getSeasonSummary(@PathVariable int season, HttpServletRequest request) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("season", season);

        long humanTeamId = userContext.getTeamId(request);

        // 1. Final league standings per competition
        List<CompetitionHistory> allHistory = competitionHistoryRepository.findAllBySeasonNumber(season);
        List<Competition> competitions = competitionRepository.findAll();
        Map<Long, String> compNames = competitions.stream()
                .collect(Collectors.toMap(Competition::getId, Competition::getName, (a, b) -> a));
        Map<Long, String> teamNames = teamRepository.findAll().stream()
                .collect(Collectors.toMap(Team::getId, Team::getName, (a, b) -> a));

        List<Map<String, Object>> standings = new ArrayList<>();
        Set<Long> processedComps = new HashSet<>();
        for (CompetitionHistory h : allHistory) {
            processedComps.add(h.getCompetitionId());
        }
        for (Long compId : processedComps) {
            List<CompetitionHistory> compStandings = allHistory.stream()
                    .filter(h -> h.getCompetitionId() == compId)
                    .sorted(Comparator.comparingLong(CompetitionHistory::getLastPosition))
                    .toList();
            Map<String, Object> compData = new LinkedHashMap<>();
            compData.put("competitionId", compId);
            compData.put("competitionName", compNames.getOrDefault(compId, "Unknown"));
            compData.put("competitionTypeId", compStandings.isEmpty() ? 0 : compStandings.get(0).getCompetitionTypeId());
            List<Map<String, Object>> teams = new ArrayList<>();
            for (CompetitionHistory ch : compStandings) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("position", ch.getLastPosition());
                t.put("teamId", ch.getTeamId());
                t.put("teamName", teamNames.getOrDefault(ch.getTeamId(), "Unknown"));
                t.put("games", ch.getGames());
                t.put("wins", ch.getWins());
                t.put("draws", ch.getDraws());
                t.put("losses", ch.getLoses());
                t.put("goalsFor", ch.getGoalsFor());
                t.put("goalsAgainst", ch.getGoalsAgainst());
                t.put("goalDifference", ch.getGoalDifference());
                t.put("points", ch.getPoints());
                t.put("isHumanTeam", ch.getTeamId() == humanTeamId);
                teams.add(t);
            }
            compData.put("teams", teams);
            standings.add(compData);
        }
        summary.put("standings", standings);

        // 2. Top scorers (from Scorer table aggregated by season)
        List<Scorer> seasonScorers =
                scorerRepository.findAllBySeasonNumberAndRoundNumberGreaterThan(season, 0);
        Map<Long, Map<String, Object>> playerGoals = new LinkedHashMap<>();
        for (Scorer s : seasonScorers) {
            Map<String, Object> entry = playerGoals.computeIfAbsent(s.getPlayerId(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("playerId", s.getPlayerId());
                m.put("teamId", s.getTeamId());
                m.put("teamName", s.getTeamName());
                m.put("goals", 0);
                m.put("assists", 0);
                m.put("games", 0);
                m.put("avgRating", 0.0);
                m.put("totalRating", 0.0);
                return m;
            });
            entry.put("goals", (int) entry.get("goals") + s.getGoals());
            entry.put("assists", (int) entry.get("assists") + s.getAssists());
            entry.put("games", (int) entry.get("games") + 1);
            entry.put("totalRating", (double) entry.get("totalRating") + s.getRating());
        }
        Map<Long, Human> playersById = humanRepository.findAllById(playerGoals.keySet()).stream()
                .collect(Collectors.toMap(Human::getId, player -> player));
        for (Map<String, Object> playerStats : playerGoals.values()) {
            int games = (int) playerStats.get("games");
            if (games > 0) {
                playerStats.put("avgRating",
                        Math.round(((double) playerStats.get("totalRating") / games) * 100.0) / 100.0);
            }
            playerStats.remove("totalRating");
            Human player = playersById.get((long) playerStats.get("playerId"));
            playerStats.put("playerName", player != null ? player.getName() : "Unknown");
            playerStats.put("position", player != null ? player.getPosition() : "");
            playerStats.put("age", player != null ? player.getAge() : 0);
        }
        List<Map<String, Object>> topScorers = playerGoals.values().stream()
                .sorted((a, b) -> Integer.compare((int) b.get("goals"), (int) a.get("goals")))
                .limit(20)
                .toList();
        summary.put("topScorers", topScorers);

        // 3. Best players by average rating (min 10 games)
        List<Map<String, Object>> bestPlayers = playerGoals.values().stream()
                .filter(m -> (int) m.get("games") >= 10)
                .sorted((a, b) -> Double.compare((double) b.get("avgRating"), (double) a.get("avgRating")))
                .limit(20)
                .toList();
        summary.put("bestPlayers", bestPlayers);

        // 4. Awards
        List<Award> awards = awardService.getAwardsForSeason(season);
        summary.put("awards", awards);

        // 5. Season objectives for human team
        List<SeasonObjective> objectives = seasonObjectiveRepository.findAllByTeamIdAndSeasonNumber(humanTeamId, season);
        summary.put("objectives", objectives);

        // 6. Transfers (human team in/out)
        List<Transfer> incomingTransfers = transferRepository.findAllByBuyTeamIdAndSeasonNumber(humanTeamId, season);
        List<Transfer> outgoingTransfers = transferRepository.findAllBySellTeamIdAndSeasonNumber(humanTeamId, season);
        summary.put("transfersIn", incomingTransfers);
        summary.put("transfersOut", outgoingTransfers);

        // 7. Top transfers league-wide (by value)
        List<Transfer> allTransfers = transferRepository.findAllBySeasonNumber(season).stream()
                .sorted(Comparator.comparingLong(Transfer::getPlayerTransferValue).reversed())
                .limit(10)
                .toList();
        summary.put("topTransfers", allTransfers);

        // 8. Manager performance
        List<ManagerHistory> managerHistory = managerHistoryRepository.findAllBySeasonNumber(season).stream()
                .filter(mh -> mh.getTeamId() == humanTeamId)
                .toList();
        summary.put("managerHistory", managerHistory);

        // 9. Human team best XI (top 11 by avg rating, min 5 games)
        List<Map<String, Object>> bestXI = playerGoals.values().stream()
                .filter(m -> (long) m.get("teamId") == humanTeamId && (int) m.get("games") >= 5)
                .sorted((a, b) -> Double.compare((double) b.get("avgRating"), (double) a.get("avgRating")))
                .limit(11)
                .toList();
        summary.put("bestXI", bestXI);

        return summary;
    }

    // ==================== LEAGUE NEWS (Feature 9) ====================

    @GetMapping("/leagueNews/{season}")
    public List<Map<String, Object>> getLeagueNews(@PathVariable int season, HttpServletRequest request) {
        List<Map<String, Object>> news = new ArrayList<>();
        long humanTeamId = userContext.getTeamId(request);

        // 1. Notable transfers in the league
        List<Transfer> allTransfers = transferRepository.findAllBySeasonNumber(season);
        List<Transfer> bigTransfers = allTransfers.stream()
                .filter(t -> t.getBuyTeamId() != humanTeamId && t.getSellTeamId() != humanTeamId)
                .sorted(Comparator.comparingLong(Transfer::getPlayerTransferValue).reversed())
                .limit(5)
                .toList();
        for (Transfer t : bigTransfers) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "transfer");
            item.put("title", t.getPlayerName() + " signs for " + t.getBuyTeamName());
            item.put("content", t.getPlayerName() + " has completed a move from " + t.getSellTeamName()
                    + " to " + t.getBuyTeamName() + " for " + formatMoney(t.getPlayerTransferValue()) + ".");
            item.put("season", season);
            news.add(item);
        }

        // 2. Manager changes (AI managers fired)
        List<ManagerHistory> allManagerHistory = managerHistoryRepository.findAllBySeasonNumber(season);
        // Not much to report here without tracking firings, but we can show manager results

        // 3. League standings summary
        List<CompetitionHistory> allHistory = competitionHistoryRepository.findAll().stream()
                .filter(h -> h.getSeasonNumber() == season)
                .toList();

        Set<Long> leagueCompIds = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 1 || c.getTypeId() == 3)
                .map(Competition::getId)
                .collect(Collectors.toSet());

        for (Long compId : leagueCompIds) {
            List<CompetitionHistory> compHistory = allHistory.stream()
                    .filter(h -> h.getCompetitionId() == compId)
                    .sorted(Comparator.comparingLong(CompetitionHistory::getLastPosition))
                    .toList();
            if (compHistory.size() >= 2) {
                CompetitionHistory champion = compHistory.get(0);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", "league_result");
                String compName = compHistory.get(0).getCompetitionName();
                Team champTeam = teamRepository.findById(champion.getTeamId()).orElse(null);
                String champName = champTeam != null ? champTeam.getName() : "Unknown";
                item.put("title", champName + " wins " + compName + "!");
                item.put("content", champName + " finished the season with " + champion.getPoints()
                        + " points, " + champion.getWins() + " wins, and a goal difference of "
                        + (champion.getGoalDifference() >= 0 ? "+" : "") + champion.getGoalDifference() + ".");
                item.put("season", season);
                news.add(item);
            }
        }

        return news;
    }

    // ==================== SAVE / LOAD (Feature 10) ====================

    @GetMapping("/export")
    public Map<String, Object> exportGame() {
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("saveVersion", CURRENT_SAVE_VERSION);
        save.put("savedAt", System.currentTimeMillis());

        Round activeRound = gameStateService.getRound();
        List<GameCalendar> activeCalendars = gameCalendarRepository.findBySeason((int) activeRound.getSeason());
        if (!activeCalendars.isEmpty()) {
            GameCalendar activeCalendar = activeCalendars.get(0);
            Map<String, Object> activeGame = new LinkedHashMap<>();
            activeGame.put("season", activeRound.getSeason());
            activeGame.put("day", activeCalendar.getCurrentDay());
            activeGame.put("phase", activeCalendar.getCurrentPhase());
            activeGame.put("dateDisplay", calendarService.getDateDisplay(activeCalendar.getCurrentDay()));
            activeGame.put("seasonPhase", activeCalendar.getSeasonPhase());
            save.put("activeGame", activeGame);
        }

        // Core state
        save.put("rounds", roundRepository.findAll());
        save.put("gameCalendars", gameCalendarRepository.findAll());
        save.put("calendarEvents", calendarEventRepository.findAll());

        // Teams & competitions
        save.put("teams", teamRepository.findAll());
        save.put("competitions", competitionRepository.findAll());
        save.put("competitionTeamInfos", competitionTeamInfoRepository.findAll());
        save.put("competitionTeamInfoDetails", competitionTeamInfoDetailRepository.findAll());
        save.put("competitionTeamInfoMatches", competitionTeamInfoMatchRepository.findAll());
        save.put("teamCompetitionDetails", teamCompetitionDetailRepository.findAll());
        save.put("competitionHistories", competitionHistoryRepository.findAll());
        save.put("teamFacilities", teamFacilitiesRepository.findAll());
        save.put("stadiums", stadiumRepository.findAll());
        save.put("clubCoefficients", clubCoefficientRepository.findAll());
        save.put("financialRecords", financialRecordRepository.findAll());

        // People
        save.put("humans", humanRepository.findAll());
        save.put("playerSkills", playerSkillsRepository.findAll());
        save.put("youthPlayers", youthPlayerRepository.findAll());
        save.put("playerInteractions", playerInteractionRepository.findAll());

        // Match data
        save.put("scorers", scorerRepository.findAll());
        save.put("scorerLeaderboard", scorerLeaderboardRepository.findAll());
        save.put("matchEvents", matchEventRepository.findAll());
        save.put("matchStats", matchStatsRepository.findAll());
        save.put("playerSeasonStats", playerSeasonStatRepository.findAll());

        // Transfers & contracts
        save.put("transfers", transferRepository.findAll());
        save.put("transferOffers", transferOfferRepository.findAll());
        save.put("loans", loanRepository.findAll());
        save.put("adminPlayerMovements", adminPlayerMovementRepository.findAll());

        // Game features
        save.put("injuries", injuryRepository.findAll());
        save.put("suspensions", suspensionRepository.findAll());
        save.put("sponsorships", sponsorshipRepository.findAll());
        save.put("boardRequests", boardRequestRepository.findAll());
        save.put("facilityUpgrades", facilityUpgradeRepository.findAll());
        save.put("awards", awardRepository.findAll());
        save.put("awardOverrides", awardOverrideRepository.findAll());
        save.put("seasonObjectives", seasonObjectiveRepository.findAll());
        save.put("managerHistories", managerHistoryRepository.findAll());
        save.put("managerInbox", managerInboxRepository.findAll());
        save.put("pressConferences", pressConferenceRepository.findAll());
        save.put("nationalTeamCallups", nationalTeamCallupRepository.findAll());
        save.put("trainingSchedules", trainingScheduleRepository.findAll());
        save.put("personalizedTactics", personalizedTacticRepository.findAll());
        save.put("teamPlayerHistorical", teamPlayerHistoricalRelationRepository.findAll());

        // V6 additions are exported from the physical H2 rows through the
        // versioned manifest. This includes every persisted canonical match
        // plan source/checkpoint and every previously omitted world-state table.
        save.putAll(gameSaveImportService.exportVersion6State());

        // Account and PersonProfile identity are deliberately outside a global
        // game save. They remain bound to the authenticated installation and
        // can never be selected or reassociated by import payload IDs.

        return save;
    }

    @PostMapping("/import")
    public Map<String, Object> importGame(@RequestBody Map<String, Object> save) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (fastForwardService.isRunning()) {
            result.put("success", false);
            result.put("error", "Cancel the active fast-forward job before loading a game");
            return result;
        }

        gameLock.lock();
        try {
            GameSaveImportService.ImportPlan plan = gameSaveImportService.prepare(save);
            // H2 ALTER ... RESTART is implicit-commit DDL, intentionally kept
            // before the rollbackable DML transaction. If any statement fails,
            // apply never starts and the previous world remains intact.
            gameSaveImportService.alignGeneratorsBeforeApply(plan);
            gameSaveImportService.apply(plan, gameplayFeatures.isPlayerAvailabilityDisabled());

            result.put("success", true);
            result.put("message", "Game loaded successfully");
            appendRestoredGameSummary(result);
            try {
                gameStateService.reloadAfterImport();
            } catch (RuntimeException reloadFailure) {
                // The database import is already committed and valid. A cache
                // refresh problem must not be misreported as a rolled-back load.
                result.put("warning", "Game loaded; runtime state will refresh on the next request");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        } finally {
            gameLock.unlock();
        }
        return result;
    }

    private void appendRestoredGameSummary(Map<String, Object> result) {
        Round restoredRound = roundRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("Imported save contains no Round row"));
        int season = (int) restoredRound.getSeason();
        GameCalendar calendar = gameCalendarRepository.findBySeason(season).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Imported save contains no calendar for season " + season));

        result.put("season", season);
        result.put("day", calendar.getCurrentDay());
        result.put("phase", calendar.getCurrentPhase());
        result.put("dateDisplay", calendarService.getDateDisplay(calendar.getCurrentDay()));
        result.put("seasonPhase", calendar.getSeasonPhase());

        List<Map<String, Object>> profiles = new ArrayList<>();
        for (User user : userRepository.findAll()) {
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("userId", user.getId());
            profile.put("username", user.getUsername());
            profile.put("teamId", user.getTeamId());
            profile.put("managerId", user.getManagerId());
            profile.put("fired", user.isFired());

            if (user.getTeamId() != null) {
                teamRepository.findById(user.getTeamId())
                        .ifPresent(team -> profile.put("teamName", team.getName()));
            }
            if (user.getManagerId() != null) {
                humanRepository.findById(user.getManagerId())
                        .ifPresent(manager -> profile.put("managerName", manager.getName()));
            }
            profiles.add(profile);
        }
        result.put("profiles", profiles);
    }

    private String formatMoney(long value) {
        if (value >= 1_000_000) return String.format("%.1fM", value / 1_000_000.0);
        if (value >= 1_000) return String.format("%.0fK", value / 1_000.0);
        return String.valueOf(value);
    }

    // TODO: remove when all callers migrated
    private long getHumanTeamId() {
        List<Round> rounds = roundRepository.findAll();
        if (!rounds.isEmpty() && rounds.get(0).getHumanTeamId() > 0) {
            return rounds.get(0).getHumanTeamId();
        }
        return 1L;
    }

    // ==================== HELPERS ====================

    private int getCurrentSeason() {
        List<GameCalendar> calendars = gameCalendarRepository.findAll();
        if (!calendars.isEmpty()) {
            return calendars.stream()
                    .mapToInt(GameCalendar::getSeason)
                    .max()
                    .orElse(1);
        }
        return 1;
    }
}
