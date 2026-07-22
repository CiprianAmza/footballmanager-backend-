package com.footballmanagergamesimulator.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.person.ControlType;
import com.footballmanagergamesimulator.person.PersonProfileRepository;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.*;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.user.UserRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/game")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class GameController {

    @PersistenceContext
    private EntityManager entityManager;

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
    @Autowired PersonProfileRepository personProfileRepository;
    @Autowired PersonProfileService personProfileService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final int LEGACY_SAVE_VERSION = 5;
    private static final int CURRENT_SAVE_VERSION = 6;

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

        // Save only career state. Authentication data and authorities are
        // account state and must never be exported into a game save.
        save.put("users", userRepository.findAll().stream().map(this::exportUserCareerState).toList());
        save.put("personProfiles", personProfileRepository.findAll());

        return save;
    }

    @PostMapping("/import")
    @Transactional
    public Map<String, Object> importGame(@RequestBody Map<String, Object> save) {
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<String> preflightError = validateSaveBeforeMutation(save);
        if (preflightError.isPresent()) {
            result.put("success", false);
            result.put("error", preflightError.get());
            return result;
        }

        if (fastForwardService.isRunning()) {
            result.put("success", false);
            result.put("error", "Cancel the active fast-forward job before loading a game");
            return result;
        }

        gameLock.lock();
        boolean unlockAfterTransaction = registerGameLockReleaseAfterTransaction();
        try {
            // Disable referential integrity and truncate all tables
            entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();

            // Get all table names from H2 and truncate them (resets identity sequences too)
            @SuppressWarnings("unchecked")
            List<Object> tables = entityManager.createNativeQuery(
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'").getResultList();
            for (Object row : tables) {
                String tableName = row instanceof Object[] ? (String) ((Object[]) row)[0] : (String) row;
                if ("FLYWAY_SCHEMA_HISTORY".equalsIgnoreCase(tableName)
                        || "USERS".equalsIgnoreCase(tableName)
                        || "PERSON_PROFILE".equalsIgnoreCase(tableName)) continue;
                entityManager.createNativeQuery("TRUNCATE TABLE \"" + tableName + "\" RESTART IDENTITY").executeUpdate();
            }

            entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
            entityManager.flush();
            entityManager.clear();

            // Import all entities using native SQL to preserve original IDs
            importNative(save, "rounds", "ROUND");
            importNative(save, "competitions", "COMPETITION");
            importNative(save, "teams", "TEAM");
            importNative(save, "teamFacilities", "TEAM_FACILITIES");
            importNative(save, "stadiums", "STADIUM");
            importNative(save, "gameCalendars", "GAME_CALENDAR");
            importNative(save, "calendarEvents", "CALENDAR_EVENT");
            importNative(save, "humans", "HUMAN");
            importNative(save, "playerSkills", "PLAYER_SKILLS");
            importNative(save, "youthPlayers", "YOUTH_PLAYER");
            importNative(save, "playerInteractions", "PLAYER_INTERACTION");
            importNative(save, "competitionTeamInfos", "COMPETITION_TEAM_INFO");
            importNative(save, "competitionTeamInfoDetails", "COMPETITION_TEAM_INFO_DETAIL");
            importNative(save, "competitionTeamInfoMatches", "COMPETITION_TEAM_INFO_MATCH");
            importNative(save, "teamCompetitionDetails", "TEAM_COMPETITION_DETAIL");
            importNative(save, "competitionHistories", "COMPETITION_HISTORY");
            importNative(save, "clubCoefficients", "CLUB_COEFFICIENT");
            importNative(save, "scorers", "SCORER");
            importNative(save, "scorerLeaderboard", "SCORER_LEADERBOARD_ENTRY");
            importNative(save, "matchEvents", "MATCH_EVENT");
            importNative(save, "matchStats", "MATCH_STATS");
            importNative(save, "playerSeasonStats", "PLAYER_SEASON_STAT");
            importNative(save, "transfers", "TRANSFER");
            importNative(save, "transferOffers", "TRANSFER_OFFER");
            importNative(save, "loans", "LOAN");
            importNative(save, "adminPlayerMovements", "ADMIN_PLAYER_MOVEMENT");
            importNative(save, "injuries", "INJURY");
            importNative(save, "suspensions", "SUSPENSION");
            importNative(save, "sponsorships", "SPONSORSHIP");
            importNative(save, "boardRequests", "BOARD_REQUEST");
            importNative(save, "facilityUpgrades", "FACILITY_UPGRADE");
            importNative(save, "awards", "AWARD");
            importNative(save, "awardOverrides", "AWARD_OVERRIDE");
            importNative(save, "seasonObjectives", "SEASON_OBJECTIVE");
            importNative(save, "managerHistories", "MANAGER_HISTORY");
            importNative(save, "managerInbox", "MANAGER_INBOX");
            importNative(save, "pressConferences", "PRESS_CONFERENCE");
            importNative(save, "nationalTeamCallups", "NATIONAL_TEAM_CALLUP");
            importNative(save, "trainingSchedules", "TRAINING_SCHEDULE");
            importNative(save, "personalizedTactics", "PERSONALIZED_TACTIC");
            importNative(save, "teamPlayerHistorical", "TEAM_PLAYER_HISTORICAL_RELATION");
            importNative(save, "financialRecords", "FINANCIAL_RECORD");
            restoreUserCareerState(save.get("users"));
            importNative(save, "personProfiles", "PERSON_PROFILE");

            Number saveVersion = (Number) save.get("saveVersion");
            if (saveVersion.intValue() == LEGACY_SAVE_VERSION) {
                entityManager.flush();
                entityManager.clear();
                personProfileService.backfill();
            }

            // Loading an older save must respect the currently selected game
            // mode. Keep historical rows, but make every player selectable.
            if (gameplayFeatures.isPlayerAvailabilityDisabled()) {
                entityManager.createNativeQuery("UPDATE SUSPENSION SET ACTIVE = FALSE").executeUpdate();
                entityManager.createNativeQuery("UPDATE INJURY SET DAYS_REMAINING = 0").executeUpdate();
                entityManager.createNativeQuery("UPDATE HUMAN SET CURRENT_STATUS = 'Available' "
                        + "WHERE LOWER(CURRENT_STATUS) LIKE 'injur%'").executeUpdate();
            }

            // Only IDENTITY columns support ALTER COLUMN ... RESTART. Trying
            // that statement on a SEQUENCE-backed primary key marks the whole
            // transaction rollback-only even when the exception is caught.
            @SuppressWarnings("unchecked")
            List<Object> identityTables = entityManager.createNativeQuery("""
                    SELECT TABLE_NAME FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = 'PUBLIC' AND COLUMN_NAME = 'ID' AND IS_IDENTITY = 'YES'
                    """).getResultList();
            for (Object row : identityTables) {
                String tableName = row instanceof Object[] ? (String) ((Object[]) row)[0] : (String) row;
                Number maxId = (Number) entityManager.createNativeQuery(
                        "SELECT COALESCE(MAX(ID), 0) FROM \"" + tableName + "\"").getSingleResult();
                entityManager.createNativeQuery("ALTER TABLE \"" + tableName
                        + "\" ALTER COLUMN ID RESTART WITH " + (maxId.longValue() + 1)).executeUpdate();
            }
            resetSequence("CTI_SEQ", "COMPETITION_TEAM_INFO");
            resetSequence("SCORER_SEQ", "SCORER");
            resetSequence("PLAYER_SKILLS_SEQ", "PLAYER_SKILLS");
            resetSequence("TPHR_SEQ", "TEAM_PLAYER_HISTORICAL_RELATION");

            entityManager.flush();
            entityManager.clear();

            result.put("success", true);
            result.put("message", "Game loaded successfully");
            appendRestoredGameSummary(result);
        } catch (Exception e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            result.put("success", false);
            result.put("error", e.getMessage());
            e.printStackTrace();
        } finally {
            if (!unlockAfterTransaction) {
                gameLock.unlock();
            }
        }
        return result;
    }

    Optional<String> validateSaveBeforeMutation(Map<String, Object> save) {
        if (save == null) return Optional.of("Invalid save: payload is required");
        Object rawVersion = save.get("saveVersion");
        if (!(rawVersion instanceof Number version)
                || (version.intValue() != LEGACY_SAVE_VERSION && version.intValue() != CURRENT_SAVE_VERSION)) {
            return Optional.of("Incompatible save version; expected 5 or 6");
        }
        if (!(save.get("rounds") instanceof List<?> rounds) || rounds.isEmpty()
                || rounds.stream().anyMatch(row -> !(row instanceof Map<?, ?>))) {
            return Optional.of("Invalid save: rounds must be a non-empty object list");
        }
        if (!(save.get("gameCalendars") instanceof List<?> calendars) || calendars.isEmpty()
                || calendars.stream().anyMatch(row -> !(row instanceof Map<?, ?>))) {
            return Optional.of("Invalid save: gameCalendars must be a non-empty object list");
        }
        Optional<String> userError = validateUsers(save.get("users"));
        if (userError.isPresent()) return userError;
        if (version.intValue() == CURRENT_SAVE_VERSION) {
            Optional<String> profileError = validateProfiles(save.get("personProfiles"));
            if (profileError.isPresent()) return profileError;
        }
        return Optional.empty();
    }

    private Optional<String> validateUsers(Object rawUsers) {
        if (rawUsers == null) return Optional.empty();
        if (!(rawUsers instanceof List<?> users)) return Optional.of("Invalid save: users must be a list");
        Set<Object> ids = new HashSet<>();
        Set<String> usernames = new HashSet<>();
        Set<String> emails = new HashSet<>();
        for (Object raw : users) {
            if (!(raw instanceof Map<?, ?> user)) return Optional.of("Invalid save: user row must be an object");
            Object id = user.get("id");
            Object username = user.get("username");
            Object email = user.get("email");
            Object password = user.get("password");
            if (!(id instanceof Number)) {
                return Optional.of("Invalid save: every user needs an id");
            }
            if (password != null && !(password instanceof String)) {
                return Optional.of("Invalid save: password value has an invalid type");
            }
            if (!ids.add(((Number) id).longValue())) {
                return Optional.of("Invalid save: duplicate user identity");
            }
            if (username != null && (!(username instanceof String name) || name.isBlank()
                    || !usernames.add(name.toLowerCase(Locale.ROOT)))) {
                return Optional.of("Invalid save: invalid or duplicate username");
            }
            if (email instanceof String value && !value.isBlank()
                    && !emails.add(value.toLowerCase(Locale.ROOT))) {
                return Optional.of("Invalid save: duplicate user email");
            }
            for (String numericField : List.of("teamId", "lastTeamId", "managerId")) {
                Object value = user.get(numericField);
                if (value != null && !(value instanceof Number)) {
                    return Optional.of("Invalid save: invalid user career state");
                }
            }
            for (String booleanField : List.of("fired", "everManaged", "initialOffersGenerated")) {
                Object value = user.get(booleanField);
                if (value != null && !(value instanceof Boolean)) {
                    return Optional.of("Invalid save: invalid user career state");
                }
            }
            Object role = user.get("careerRole");
            if (role != null) {
                try {
                    com.footballmanagergamesimulator.user.CareerRole.valueOf(String.valueOf(role));
                } catch (IllegalArgumentException exception) {
                    return Optional.of("Invalid save: unsupported career role");
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> validateProfiles(Object rawProfiles) {
        if (!(rawProfiles instanceof List<?> profiles)) {
            return Optional.of("Invalid save v6: personProfiles must be a list");
        }
        Set<Long> ids = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        Set<Long> humanIds = new HashSet<>();
        for (Object raw : profiles) {
            if (!(raw instanceof Map<?, ?> profile) || !(profile.get("id") instanceof Number id)) {
                return Optional.of("Invalid save v6: profile row must contain an id");
            }
            if (!ids.add(id.longValue())) return Optional.of("Invalid save v6: duplicate profile id");
            if (!addUniqueNullable(profile.get("userId"), userIds)
                    || !addUniqueNullable(profile.get("humanId"), humanIds)) {
                return Optional.of("Invalid save v6: duplicate profile identity link");
            }
            try {
                CareerType.valueOf(String.valueOf(profile.get("careerType")));
                ControlType.valueOf(String.valueOf(profile.get("controlType")));
            } catch (IllegalArgumentException exception) {
                return Optional.of("Invalid save v6: unsupported profile type");
            }
        }
        return Optional.empty();
    }

    private boolean addUniqueNullable(Object raw, Set<Long> values) {
        if (raw == null) return true;
        return raw instanceof Number number && values.add(number.longValue());
    }

    private Map<String, Object> exportUserCareerState(User user) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", user.getId());
        state.put("teamId", user.getTeamId());
        state.put("lastTeamId", user.getLastTeamId());
        state.put("managerId", user.getManagerId());
        state.put("fired", user.isFired());
        state.put("everManaged", user.isEverManaged());
        state.put("initialOffersGenerated", user.isInitialOffersGenerated());
        return state;
    }

    private void restoreUserCareerState(Object rawUsers) {
        if (!(rawUsers instanceof List<?> users)) return;
        Map<String, String> allowed = Map.of(
                "teamId", "TEAM_ID",
                "lastTeamId", "LAST_TEAM_ID",
                "managerId", "MANAGER_ID",
                "fired", "FIRED",
                "everManaged", "EVER_MANAGED",
                "initialOffersGenerated", "INITIAL_OFFERS_GENERATED");
        for (Object raw : users) {
            if (!(raw instanceof Map<?, ?> row) || !(row.get("id") instanceof Number id)) continue;
            List<String> assignments = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            for (Map.Entry<String, String> field : allowed.entrySet()) {
                if (!row.containsKey(field.getKey())) continue;
                assignments.add(field.getValue() + " = ?");
                values.add(row.get(field.getKey()));
            }
            if (assignments.isEmpty()) continue;
            jakarta.persistence.Query query = entityManager.createNativeQuery(
                    "UPDATE USERS SET " + String.join(", ", assignments) + " WHERE ID = ?");
            for (int index = 0; index < values.size(); index++) {
                query.setParameter(index + 1, values.get(index));
            }
            query.setParameter(values.size() + 1, id.longValue());
            query.executeUpdate();
        }
    }

    private boolean registerGameLockReleaseAfterTransaction() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                gameStateService.reloadAfterImport();
            }

            @Override
            public void afterCompletion(int status) {
                gameLock.unlock();
            }
        });
        return true;
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

    private void resetSequence(String sequenceName, String tableName) {
        Number maxId = (Number) entityManager.createNativeQuery(
                "SELECT COALESCE(MAX(ID), 0) FROM \"" + tableName + "\"").getSingleResult();
        entityManager.createNativeQuery("ALTER SEQUENCE " + sequenceName
                + " RESTART WITH " + (maxId.longValue() + 1)).executeUpdate();
    }

    // Global JSON property name -> SQL column name overrides
    // ONLY for mappings that are safe across ALL tables
    private static final Map<String, String> COLUMN_NAME_OVERRIDES = Map.ofEntries(
            Map.entry("substitute", "IS_SUBSTITUTE")    // Lombok: boolean isSubstitute -> getter isSubstitute() -> Jackson key 'substitute'
    );

    // Per-table overrides for @Column(name=...) and boolean field naming mismatches
    private static final Map<String, Map<String, String>> TABLE_COLUMN_OVERRIDES = Map.ofEntries(
            // @Column(name=...) overrides
            Map.entry("AWARD", Map.of("value", "AWARD_VALUE")),
            Map.entry("BOARD_REQUEST", Map.of("day", "REQUEST_DAY", "status", "REQUEST_STATUS")),
            Map.entry("CALENDAR_EVENT", Map.of("day", "EVENT_DAY", "status", "EVENT_STATUS")),
            Map.entry("COMPETITION_TEAM_INFO_MATCH", Map.of(
                    "day", "MATCH_DAY",
                    "team1Score", "TEAM1_SCORE",
                    "team2Score", "TEAM2_SCORE")),
            Map.entry("COMPETITION_TEAM_INFO_DETAIL", Map.of("day", "MATCH_DAY")),
            Map.entry("FINANCIAL_RECORD", Map.of("day", "RECORD_DAY")),
            Map.entry("MATCH_EVENT", Map.of("minute", "EVENT_MINUTE")),
            Map.entry("PLAYER_INTERACTION", Map.of("day", "INTERACTION_DAY")),
            Map.entry("PRESS_CONFERENCE", Map.of("day", "CONFERENCE_DAY")),
            // Boolean field overrides (Jackson+Lombok produce both "isX" and "x" keys)
            // Human: boolean retired + @JsonProperty("isRetired") -> column RETIRED
            Map.entry("HUMAN", Map.of("isRetired", "RETIRED", "retired", "RETIRED")),
            // ScorerLeaderboardEntry: boolean isActive + @JsonProperty("isActive") -> column IS_ACTIVE
            Map.entry("SCORER_LEADERBOARD_ENTRY", Map.of("isActive", "IS_ACTIVE", "active", "IS_ACTIVE")),
            // ManagerInbox: boolean isRead + @JsonProperty("isRead") -> column IS_READ
            Map.entry("MANAGER_INBOX", Map.of("isRead", "IS_READ", "read", "IS_READ"))
            // Suspension: boolean active -> column ACTIVE (default camelToSnake works, no override needed)
    );

    @SuppressWarnings("unchecked")
    private void importNative(Map<String, Object> save, String jsonKey, String tableName) {
        Object data = save.get(jsonKey);
        if (data == null) return;

        // First, get actual column names from H2 schema for this table
        Set<String> validColumns = new HashSet<>();
        try {
            @SuppressWarnings("unchecked")
            List<Object> cols = entityManager.createNativeQuery(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = '" + tableName + "' AND TABLE_SCHEMA = 'PUBLIC'").getResultList();
            for (Object col : cols) {
                String colName = col instanceof Object[] ? (String) ((Object[]) col)[0] : (String) col;
                validColumns.add(colName.toUpperCase());
            }
        } catch (Exception e) {
            System.err.println("Could not get columns for " + tableName + ": " + e.getMessage());
            return;
        }

        if (validColumns.isEmpty()) {
            System.err.println("No columns found for table " + tableName + ", skipping");
            return;
        }

        List<?> rows = (List<?>) data;
        for (Object rowObj : rows) {
            Map<String, Object> row = objectMapper.convertValue(rowObj, new TypeReference<Map<String, Object>>() {});

            List<String> columns = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            Set<String> addedColumns = new HashSet<>(); // prevent duplicate columns (Jackson+Lombok can emit both "isActive" and "active")

            // Get table-specific overrides if any
            Map<String, String> tableOverrides = TABLE_COLUMN_OVERRIDES.getOrDefault(tableName, Map.of());

            for (Map.Entry<String, Object> entry : row.entrySet()) {
                Object val = entry.getValue();
                if (val == null) continue;

                // Map JSON key to SQL column name: table-specific override > global override > camelToSnake
                String colName;
                if (tableOverrides.containsKey(entry.getKey())) {
                    colName = tableOverrides.get(entry.getKey());
                } else if (COLUMN_NAME_OVERRIDES.containsKey(entry.getKey())) {
                    colName = COLUMN_NAME_OVERRIDES.get(entry.getKey());
                } else {
                    colName = camelToSnake(entry.getKey()).toUpperCase();
                }

                // Only include columns that actually exist in the table, and skip duplicates
                if (!validColumns.contains(colName)) continue;
                if (!addedColumns.add(colName)) continue;

                columns.add(colName);
                values.add(val);
            }

            if (columns.isEmpty()) continue;

            // Person profiles survive the table truncation and are updated by
            // identity. User account rows never pass through this generic
            // importer; only an explicit allow-list of career fields is restored.
            String sql;
            if ("PERSON_PROFILE".equals(tableName)) {
                sql = "MERGE INTO " + tableName + " (" + String.join(", ", columns)
                        + ") KEY(ID) VALUES ("
                        + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
            } else {
                sql = "INSERT INTO " + tableName + " (" + String.join(", ", columns)
                        + ") VALUES ("
                        + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
            }

            jakarta.persistence.Query query = entityManager.createNativeQuery(sql);
            for (int i = 0; i < values.size(); i++) {
                query.setParameter(i + 1, values.get(i));
            }
            query.executeUpdate();
        }
    }

    private String camelToSnake(String str) {
        // Mimics Spring Boot's CamelCaseToUnderscoresNamingStrategy:
        // Insert underscore before uppercase letter ONLY if preceded by a lowercase letter
        // Digits do NOT trigger underscore: team1Id -> team1id -> TEAM1ID (matches H2 column)
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && Character.isLowerCase(str.charAt(i - 1))) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
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
