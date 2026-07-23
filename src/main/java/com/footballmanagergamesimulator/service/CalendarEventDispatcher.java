package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.controller.ScoutManagementController;
import com.footballmanagergamesimulator.model.Award;
import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.PressConference;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamFacilities;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.InjuryRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamFacilitiesRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.user.UserRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import com.footballmanagergamesimulator.economy.PersonalPayrollService;
import com.footballmanagergamesimulator.economy.DeterministicMarketPriceService;
import com.footballmanagergamesimulator.economy.RegentEconomyProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Calendar event dispatcher: owns the big switch over {@link CalendarEvent#getEventType()},
 * plus the per-event-type helpers it delegates to (season lifecycle, monthly wages,
 * injury recovery, AI batched training).
 *
 * <p>Extracted from {@link GameAdvanceService} as part of §6.5b. The advance loop
 * itself stays in {@link GameAdvanceService}; this class is its per-event worker.
 */
@Service
public class CalendarEventDispatcher {

    @Autowired private UserContext userContext;
    @Autowired private CalendarService calendarService;
    @Autowired private GameCalendarRepository gameCalendarRepository;
    @Autowired private CalendarEventRepository calendarEventRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamFacilitiesRepository teamFacilitiesRepository;
    @Autowired private CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private com.footballmanagergamesimulator.config.CompetitionFormatConfig competitionFormat;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private HumanService humanService;

    @Autowired @Lazy private SeasonTransitionService seasonTransitionService;
    @Autowired @Lazy private EndOfSeasonProcessor endOfSeasonProcessor;
    @Autowired @Lazy private MatchSimulationOrchestrator matchSimulationOrchestrator;
    @Autowired private TransferMarketService transferMarketService;
    @Autowired private FixtureSchedulingService fixtureSchedulingService;
    @Autowired private EuropeanFixturePreparationService europeanFixturePreparationService;
    @Autowired private EuropeanDrawService europeanDrawService;
    @Autowired @Lazy private TrainingService trainingService;
    @Autowired @Lazy private YouthAcademyService youthAcademyService;
    @Autowired @Lazy private PressConferenceService pressConferenceService;
    @Autowired @Lazy private BoardRequestService boardRequestService;
    @Autowired @Lazy private AwardService awardService;
    @Autowired @Lazy private SponsorshipService sponsorshipService;
    @Autowired @Lazy private FacilityUpgradeService facilityUpgradeService;
    @Autowired @Lazy private NationalTeamService nationalTeamService;
    @Autowired @Lazy private SuspensionService suspensionService;
    @Autowired @Lazy private ScoutManagementController scoutManagementController;
    @Autowired private LiveMatchSimulationService liveMatchSimulationService;
    @Autowired @Lazy private FinanceService financeService;
    @Autowired private PersonalPayrollService personalPayrollService;
    @Autowired private RegentEconomyProperties regentEconomyProperties;
    @Autowired private DeterministicMarketPriceService deterministicMarketPriceService;
    @Autowired @Lazy private FriendlyMatchService friendlyMatchService;
    @Autowired private com.footballmanagergamesimulator.config.MatchEngineConfig engineConfig;
    @Autowired private InjuryTimelineService injuryTimelineService;
    @Autowired private TrainingCadenceService trainingCadenceService;

    // Cache of competition IDs all human teams participate in (per season)
    private Set<Long> humanTeamCompetitionIds = null;
    private int humanTeamCompetitionIdsSeason = -1;

    private Set<Long> getHumanTeamCompetitionIds(int season) {
        if (humanTeamCompetitionIds != null && humanTeamCompetitionIdsSeason == season) {
            return humanTeamCompetitionIds;
        }
        Set<Long> allCompIds = new HashSet<>();
        for (long htId : userContext.getAllHumanTeamIds()) {
            List<CompetitionTeamInfoMatch> matches = competitionTeamInfoMatchRepository
                    .findAllBySeasonNumberAndTeamId(String.valueOf(season), htId);
            matches.stream()
                    .map(CompetitionTeamInfoMatch::getCompetitionId)
                    .forEach(allCompIds::add);
        }
        humanTeamCompetitionIds = allCompIds;
        humanTeamCompetitionIdsSeason = season;
        return humanTeamCompetitionIds;
    }

    /** Invalidate the human-competitions cache (called after season transition). */
    public void invalidateHumanCompetitionsCache() {
        humanTeamCompetitionIds = null;
        humanTeamCompetitionIdsSeason = -1;
    }

    // ============================================================
    //  Main switch
    // ============================================================

    public Map<String, Object> processEvent(CalendarEvent event, GameCalendar calendar) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", event.getEventType());
        result.put("title", event.getTitle());
        result.put("day", event.getDay());

        switch (event.getEventType()) {
            case "TRAINING_SESSION":
                for (long htId : userContext.getAllHumanTeamIds()) {
                    trainingService.processTrainingSession(
                            htId, calendar.getSeason(), calendar.getCurrentDay());
                }
                // Train AI teams using the original trainPlayer logic
                trainAllAITeams(calendar.getSeason(), calendar.getCurrentDay());
                result.put("details", "Training session completed");
                break;
            case "YOUTH_ACADEMY_REPORT":
                for (long htId : userContext.getAllHumanTeamIds()) {
                    youthAcademyService.generateYouthReport(htId, calendar.getSeason());
                    youthAcademyService.developYouthPlayers(htId);
                }
                result.put("details", "Youth academy report generated");
                break;
            case "PRESS_CONFERENCE":
                // Find the first human team that plays in this competition/round and show press conference for them
                long pressCompId = event.getCompetitionId() != null ? event.getCompetitionId() : 0;
                Set<Long> humanCompIds = getHumanTeamCompetitionIds(calendar.getSeason());

                if (pressCompId > 0 && !humanCompIds.contains(pressCompId)) {
                    result.put("details", "Press conference skipped (not in competition)");
                    result.put("userRelevant", false);
                    break;
                }

                Long pressHumanTeamId = null;
                for (long htId : userContext.getAllHumanTeamIds()) {
                    // Check if manager has delegated press conferences to assistant
                    List<Human> humanManagers = humanRepository.findAllByTeamIdAndTypeId(htId, TypeNames.MANAGER_TYPE);
                    Human currentManager = resolveCurrentManager(htId, humanManagers);
                    if (currentManager != null
                            && (!currentManager.isAttendPressConferences() || currentManager.isAlwaysContinue())) {
                        continue;
                    }

                    // Check if this human team actually plays in this round of this competition
                    if (pressCompId > 0 && event.getMatchday() > 0) {
                        List<CompetitionTeamInfoMatch> roundMatches = competitionTeamInfoMatchRepository
                                .findAllByCompetitionIdAndRoundAndSeasonNumber(pressCompId, event.getMatchday(), String.valueOf(calendar.getSeason()));
                        boolean humanPlaysInRound = roundMatches.stream()
                                .anyMatch(m -> m.getTeam1Id() == htId || m.getTeam2Id() == htId);
                        if (humanPlaysInRound) {
                            pressHumanTeamId = htId;
                            break;
                        }
                    }
                }

                if (pressHumanTeamId == null) {
                    result.put("details", "Press conference skipped (team not playing in this round)");
                    result.put("userRelevant", false);
                    break;
                }

                PressConference pc = pressConferenceService.generatePreMatchPressConference(pressHumanTeamId,
                        pressCompId, event.getMatchday(), calendar.getSeason());
                result.put("details", "Press conference available");
                result.put("pressConferenceId", pc.getId());
                result.put("awaitingInput", true);
                // Mark all other PRESS_CONFERENCE events for this day as completed
                // so only one press conference modal appears per day
                List<CalendarEvent> otherPressConfs = calendarEventRepository
                        .findAllBySeasonAndDayAndStatus(calendar.getSeason(), calendar.getCurrentDay(), "PENDING")
                        .stream()
                        .filter(e -> "PRESS_CONFERENCE".equals(e.getEventType()) && e.getId() != event.getId())
                        .toList();
                for (CalendarEvent other : otherPressConfs) {
                    calendarService.markEventCompleted(other.getId());
                }
                break;
            case "BOARD_MEETING":
                for (long htId : userContext.getAllHumanTeamIds()) {
                    boardRequestService.processBoardMeeting(htId, calendar.getSeason());
                }
                result.put("details", "Board meeting completed");
                break;
            case "AWARDS_CEREMONY":
                List<Award> awards = awardService.processAwardsCeremony(calendar.getSeason());
                result.put("details", "Awards ceremony completed");
                result.put("awards", awards);
                break;
            case "SPONSOR_OFFER":
                for (long htId : userContext.getAllHumanTeamIds()) {
                    sponsorshipService.generateSponsorOffer(htId, calendar.getSeason());
                }
                result.put("details", "Sponsorship offer received");
                break;
            case "NATIONAL_TEAM_CALL":
                nationalTeamService.processInternationalBreak(calendar.getSeason(), calendar.getCurrentDay());
                result.put("details", "International break - players called up");
                break;
            case "INJURY_UPDATE":
                // Compatibility for calendars generated before absolute return dates.
                injuryTimelineService.processRecoveries(calendar.getSeason(), calendar.getCurrentDay());
                processDailyMaintenance(calendar.getSeason(), calendar.getCurrentDay());
                result.put("details", "Injuries and facilities updated");
                break;
            case "EUROPEAN_DRAW":
                if (event.getCompetitionId() == null || event.getMatchday() <= 0) {
                    result.put("details", "European draw skipped: missing competition data");
                    break;
                }
                result.putAll(europeanDrawService.executeDraw(event, calendar));
                break;
            case "MATCH_LEAGUE":
            case "MATCH_CUP":
            case "MATCH_EUROPEAN":
                if (event.getCompetitionId() != null && event.getMatchday() > 0) {
                    matchSimulationOrchestrator.simulateMatchday(
                            event.getCompetitionId(), event.getMatchday(), event.getSeason());
                    result.put("details", "Match simulated: " + event.getTitle());
                    // Fetch match results for ALL human teams
                    List<Long> htIds = userContext.getAllHumanTeamIds();
                    Map<Long, Map<String, Object>> allMatchResults = new LinkedHashMap<>();
                    for (long htId : htIds) {
                        Map<String, Object> matchResult = matchSimulationOrchestrator.getHumanMatchResult(
                                event.getCompetitionId(), event.getMatchday(), event.getSeason(), htId);
                        if (matchResult.containsKey("score")) {
                            allMatchResults.put(htId, matchResult);
                        }
                    }
                    if (!allMatchResults.isEmpty()) {
                        result.put("allMatchResults", allMatchResults);
                        // Backward compat: also put first result as "matchResult"
                        result.put("matchResult", allMatchResults.values().iterator().next());

                        // Check if any human team has a live match available
                        for (long htId : htIds) {
                            Map<String, Object> mr = allMatchResults.get(htId);
                            if (mr != null && mr.containsKey("team1Id") && mr.containsKey("team2Id")) {
                                long t1 = ((Number) mr.get("team1Id")).longValue();
                                long t2 = ((Number) mr.get("team2Id")).longValue();
                                String liveKey = LiveMatchSimulationService.buildKey(
                                        event.getCompetitionId(), event.getSeason(),
                                        event.getMatchday(), t1, t2);
                                if (liveMatchSimulationService.getLiveMatchData(liveKey) != null) {
                                    result.put("hasLiveMatch", true);
                                    result.put("liveMatchKey", liveKey);
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    result.put("details", "Match day - missing competition data");
                }
                // Legacy single-event path uses the same idempotent per-fixture
                // discipline lifecycle as Continue/Fast Forward.
                competitionRepository.findById(event.getCompetitionId()).ifPresent(competition -> {
                    int round = competitionFormat.get((int) competition.getTypeId())
                            .roundForMatchday(event.getMatchday());
                    competitionTeamInfoMatchRepository.findAllByCompetitionIdAndRoundAndSeasonNumber(
                                    event.getCompetitionId(), round, String.valueOf(event.getSeason())).stream()
                            .filter(fixture -> event.getLegNumber() == 0
                                    || fixture.getLegNumber() == event.getLegNumber())
                            .filter(fixture -> fixture.getTeam1Score() >= 0 && fixture.getTeam2Score() >= 0)
                            .forEach(fixture -> suspensionService.processPlayedFixture(fixture, event.getSeason()));
                });
                break;
            case "MATCH_FRIENDLY":
                List<Map<String, Object>> friendlyResults = friendlyMatchService.simulateFriendliesForDay(
                        calendar.getSeason(), calendar.getCurrentDay());
                if (!friendlyResults.isEmpty()) {
                    result.put("details", "Friendly match completed");
                    result.put("friendlyResults", friendlyResults);
                    // Show first result as summary
                    Map<String, Object> first = friendlyResults.get(0);
                    result.put("title", "Friendly: " + first.get("homeTeam") + " " +
                            first.get("score") + " " + first.get("awayTeam"));
                } else {
                    result.put("details", "Pre-season friendly day (no matches scheduled)");
                }
                break;
            case "CONTRACT_EXPIRY_CHECK":
                endOfSeasonProcessor.handleContractExpiries((int) calendar.getSeason());
                result.put("details", "Contract expiry check completed - expired contracts processed");
                break;
            case "ANALYTICS_REPORT":
                result.put("details", "Analytics report available");
                break;
            case "TRANSFER_WINDOW_OPEN":
                calendar.setTransferWindowOpen(true);
                gameCalendarRepository.save(calendar);
                transferMarketService.setOpen(true);
                result.put("details", "Transfer window is now open! You can buy and sell players.");
                result.put("awaitingInput", true);
                break;
            case "TRANSFER_WINDOW_CLOSE":
                calendar.setTransferWindowOpen(false);
                gameCalendarRepository.save(calendar);
                transferMarketService.setOpen(false);
                result.put("details", "Transfer window is now closed.");
                break;
            case "SEASON_START":
                processSeasonStart(event.getSeason());
                result.put("details", "Season " + event.getSeason() + " initialized - calendar generated");
                break;
            case "SEASON_END":
                processSeasonEnd(event.getSeason(), calendar);
                result.put("details", "Season " + event.getSeason() + " has ended. AI transfers completed. Transfer window is now open!");
                result.put("awaitingInput", true);
                break;
            case "SEASON_TRANSITION":
                processSeasonTransition(event.getSeason());
                result.put("details", "New season " + (event.getSeason() + 1) + " is starting!");
                result.put("newSeason", event.getSeason() + 1);
                result.put("awaitingInput", true);
                break;
            default:
                result.put("details", event.getDescription());
                break;
        }

        return result;
    }

    /** Work that must run once per calendar day but does not need a persisted event row. */
    public void processDailyMaintenance(int season, int day) {
        for (long teamId : userContext.getAllHumanTeamIds()) {
            facilityUpgradeService.checkUpgradeCompletion(teamId, day, season);
        }
        scoutManagementController.processCompletedAssignments(season, day);
        deterministicMarketPriceService.processDay(season, day);
    }

    private Human resolveCurrentManager(long teamId, List<Human> managers) {
        if (managers.isEmpty()) return null;
        Long linkedManagerId = userRepository.findAllByTeamId(teamId).stream()
                .map(User::getManagerId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (linkedManagerId == null) return managers.get(0);
        return managers.stream()
                .filter(manager -> manager.getId() == linkedManagerId)
                .findFirst()
                .orElse(managers.get(0));
    }

    // ============================================================
    //  Monthly + daily housekeeping (called from advance loop)
    // ============================================================

    public void processMonthlyWages(int season) {
        GameCalendar calendar = calendarService.getOrCreateCalendar(season);
        int currentDay = calendar.getCurrentDay();

        List<Team> allTeams = teamRepository.findAll();
        for (Team team : allTeams) {
            // Process wages through finance service (records transaction + updates totalFinances)
            long wagesPaid = regentEconomyProperties.isEnabled()
                    ? personalPayrollService.processTeam(team, season, currentDay)
                    : financeService.processTeamMonthlyWages(team, season, currentDay);

            // Process monthly merchandising income for all teams
            financeService.processMerchandisingIncome(team.getId(), season, currentDay);

            // Process debt interest if applicable
            financeService.processDebtInterest(team.getId(), season, currentDay);

            // Check if finances went negative -> create debt
            financeService.checkAndCreateDebt(team.getId());

            // Credit the team's manager their monthly salary into career earnings.
            if (!regentEconomyProperties.isEnabled()) accrueManagerSalary(team);

            // Send financial report to human managers
            if (userContext.isHumanTeam(team.getId())) {
                financeService.sendMonthlyFinancialReport(team.getId(), season, currentDay, wagesPaid);
            }
        }
        System.out.println("=== Monthly wages processed for season " + season + " ===");
    }

    /**
     * Pays a team's manager their monthly salary, accumulating it into the
     * manager's {@code careerEarnings}. Salary is lazily derived from the club's
     * reputation the first time it's unset, so existing/seeded managers get a
     * sensible wage without touching every creation site.
     */
    private void accrueManagerSalary(Team team) {
        List<Human> managers = humanRepository.findAllByTeamIdAndTypeId(team.getId(), TypeNames.MANAGER_TYPE);
        for (Human mgr : managers) {
            if (mgr.isRetired()) continue;
            long salary = mgr.getSalary();
            if (salary <= 0) {
                // Reputation 0-10000 -> ~€2k..€50k/month.
                salary = Math.max(2000L, team.getReputation() * 5L);
                mgr.setSalary(salary);
            }
            mgr.setCareerEarnings(mgr.getCareerEarnings() + salary);
            humanRepository.save(mgr);
        }
    }

    // ============================================================
    //  AI batched training (runs from TRAINING_SESSION case)
    // ============================================================

    /**
     * Trains all AI team players using batch-loaded data.
     * Runs only on configured rating-update days (21 days by default).
     * Uses bulk queries instead of per-player/per-team queries (~5 queries total vs ~10,000).
     */
    private void trainAllAITeams(int season, int currentDay) {
        if (!trainingCadenceService.isRatingUpdateDay(currentDay)) return;

        // 1. Determine which teams are AI teams
        List<Team> allTeams = teamRepository.findAll();
        Set<Long> aiTeamIds = new HashSet<>();
        for (Team team : allTeams) {
            if (!userContext.isHumanTeam(team.getId())) {
                aiTeamIds.add(team.getId());
            }
        }
        if (aiTeamIds.isEmpty()) return;

        // 2. Bulk load all facilities (1 query)
        List<TeamFacilities> allFacilities = teamFacilitiesRepository.findAll();
        Map<Long, TeamFacilities> facilitiesMap = new HashMap<>();
        for (TeamFacilities f : allFacilities) {
            facilitiesMap.put(f.getTeamId(), f);
        }

        // 3. Bulk load all AI team players (1 query)
        List<Human> allPlayers = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE);
        List<Human> aiPlayers = new ArrayList<>();
        for (Human p : allPlayers) {
            if (p.getTeamId() != null && aiTeamIds.contains(p.getTeamId()) && !p.isRetired()) {
                aiPlayers.add(p);
            }
        }
        if (aiPlayers.isEmpty()) return;

        // 4. Bulk load all PlayerSkills for AI players (1 query)
        List<Long> aiPlayerIds = aiPlayers.stream().map(Human::getId).collect(Collectors.toList());
        List<PlayerSkills> allSkills = playerSkillsRepository.findAllByPlayerIdIn(aiPlayerIds);
        Map<Long, PlayerSkills> skillsMap = new HashMap<>();
        for (PlayerSkills ps : allSkills) {
            skillsMap.put(ps.getPlayerId(), ps);
        }

        // 5. Bulk load all coaching staff and pre-compute multiplier per team (1 query per coach type = 6 queries)
        Map<Long, Double> coachingMultiplierCache = new HashMap<>();
        // Pre-load all staff in one pass by loading all humans with coach types
        Map<Long, List<Human>> staffByTeam = new HashMap<>();
        for (long coachType : new long[]{TypeNames.ASSISTANT_MANAGER_TYPE, TypeNames.FIRST_TEAM_COACH_TYPE,
                TypeNames.FITNESS_COACH_TYPE, TypeNames.GK_COACH_TYPE,
                TypeNames.YOUTH_COACH_TYPE, TypeNames.HOYD_TYPE}) {
            for (Human staff : humanRepository.findAllByTypeId(coachType)) {
                if (staff.getTeamId() != null && aiTeamIds.contains(staff.getTeamId())) {
                    staffByTeam.computeIfAbsent(staff.getTeamId(), k -> new ArrayList<>()).add(staff);
                }
            }
        }

        for (long teamId : aiTeamIds) {
            List<Human> staff = staffByTeam.getOrDefault(teamId, List.of());
            if (staff.isEmpty()) {
                coachingMultiplierCache.put(teamId, 0.5);
            } else {
                double avg = staff.stream()
                        .mapToDouble(c -> (c.getCoachingAttacking() + c.getCoachingDefending() +
                                c.getCoachingTactical() + c.getCoachingTechnical() +
                                c.getCoachingMental() + c.getCoachingFitness()) / 6.0)
                        .average().orElse(5.0);
                coachingMultiplierCache.put(teamId, 0.5 + (avg / 20.0));
            }
        }

        // 6. Train all players in memory
        Random random = new Random();
        List<Human> modifiedPlayers = new ArrayList<>();
        List<PlayerSkills> modifiedSkills = new ArrayList<>();

        for (Human player : aiPlayers) {
            long teamId = player.getTeamId();
            TeamFacilities facilities = facilitiesMap.get(teamId);
            if (facilities == null) continue;

            double staffMult = coachingMultiplierCache.getOrDefault(teamId, 0.5);
            double facilityBase = com.footballmanagergamesimulator.config.FacilityTraining
                    .developmentFactor(engineConfig.getTraining(), facilities, player.getAge());
            double facilityMultiplier = staffMult * 0.6 + facilityBase * 0.4;

            PlayerSkills skills = skillsMap.get(player.getId());
            PlayerSkills changedSkills = humanService.trainPlayerBatch(
                    player, facilities, facilityMultiplier, skills, season, random);

            modifiedPlayers.add(player);
            if (changedSkills != null) {
                modifiedSkills.add(changedSkills);
            }
        }

        // 7. Batch save (2 queries)
        if (!modifiedPlayers.isEmpty()) humanRepository.saveAll(modifiedPlayers);
        if (!modifiedSkills.isEmpty()) playerSkillsRepository.saveAll(modifiedSkills);
    }

    // ============================================================
    //  Season lifecycle (called from event switch + safety net)
    // ============================================================

    /**
     * Processes SEASON_START event: generates the full season calendar with all fixtures,
     * training sessions and other periodic events.
     */
    private void processSeasonStart(int season) {
        System.out.println("=== SEASON_START: Generating calendar for season " + season + " ===");

        // Check if calendar events already exist for this season (avoid duplicates)
        // (the SEASON_START event itself exists, so check for more than just a few events)
        long totalEvents = calendarEventRepository.findAllBySeasonAndDayAndStatus(season, 2, "PENDING").size();
        if (totalEvents == 0) {
            fixtureSchedulingService.generateSeasonCalendar(season);
            System.out.println("=== Season " + season + " calendar generated ===");
        } else {
            System.out.println("=== Season " + season + " calendar already exists, skipping generation ===");
        }

        // European fixtures are deliberately not drawn here. Their dedicated
        // EUROPEAN_DRAW events reveal the pairings seven days before each stage.
    }

    /**
     * Processes SEASON_END event (day 340): Phase 1 of season transition.
     * Handles final standings, relegation/promotion, AI transfers, and loans.
     * The game pauses here so the user can make transfers (days 341-355).
     * The new season is NOT created yet — that happens at SEASON_TRANSITION (day 360).
     */
    private void processSeasonEnd(int season, GameCalendar calendar) {
        System.out.println("=== SEASON_END: Processing end of season " + season + " ===");

        // Phase 1: standings, relegation, AI transfers
        seasonTransitionService.processEndOfSeason(season);

        // Set transfer window open on the current calendar
        calendar.setTransferWindowOpen(true);
        gameCalendarRepository.save(calendar);

        System.out.println("=== Season " + season + " ended. Transfer window open for user transfers. ===");
    }

    /**
     * Processes SEASON_TRANSITION event (day 360): Phase 2 of season transition.
     * Called AFTER the transfer window closes. Sets up the new season:
     * budget refresh, aging, regens, fixtures, scorers, new calendar.
     *
     * <p>Also invoked from the advance-loop safety net in {@link GameAdvanceService},
     * so this is public.
     */
    public void processSeasonTransition(int season) {
        System.out.println("=== SEASON_TRANSITION: Creating new season from " + season + " ===");

        int newSeason = season + 1;

        // Guard: check if the new season calendar already exists (prevents double processing)
        List<GameCalendar> existingNewCal = gameCalendarRepository.findBySeason(newSeason);
        if (!existingNewCal.isEmpty()) {
            System.out.println("=== Season " + newSeason + " calendar already exists, skipping ===");
            return;
        }

        // Phase 2: aging, regens, fixtures, scorers, budget refresh. If this
        // fails, propagate the error and keep the event retryable; never publish
        // an empty/partial new-season calendar.
        seasonTransitionService.processNewSeasonSetup(season);

        // Create a new GameCalendar for the new season
        GameCalendar newCalendar = new GameCalendar();
        newCalendar.setSeason(newSeason);
        newCalendar.setCurrentDay(1);
        newCalendar.setCurrentPhase("MORNING");
        newCalendar.setSeasonPhase("PRE_SEASON");
        newCalendar.setTransferWindowOpen(false);
        newCalendar.setManagerFired(false);
        newCalendar.setPaused(false);
        gameCalendarRepository.save(newCalendar);

        // Generate the calendar events for the new season
        fixtureSchedulingService.generateSeasonCalendar(newSeason);

        // Invalidate cached competition IDs since season changed
        invalidateHumanCompetitionsCache();

        // The product calendar contains routine events through day 365, while
        // the successful season transition happens on day 360. Those remaining
        // rows can never be reached after the client switches to newSeason, so
        // archive them explicitly instead of leaving retryable PENDING debris.
        int skippedEvents = calendarEventRepository.skipPendingEventsAfter(season, 360);

        System.out.println("=== Season transition complete. New season " + newSeason + " started. ===");
        if (skippedEvents > 0) {
            System.out.println("=== Archived " + skippedEvents
                    + " unreachable calendar events after season " + season + " transition. ===");
        }
    }
}
