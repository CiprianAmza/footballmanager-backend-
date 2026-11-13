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
import com.footballmanagergamesimulator.config.ChairmanModeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    @Autowired private TransferInstallmentService transferInstallmentService;
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
    @Autowired private CompetitionExpectationService competitionExpectationService;
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
    @Autowired private FinancialTroubleMediaService financialTroubleMediaService;
    @Autowired private PersonalPayrollService personalPayrollService;
    @Autowired private ChairmanModeProperties chairmanModeProperties;
    @Autowired private DeterministicMarketPriceService deterministicMarketPriceService;
    @Autowired @Lazy private FriendlyMatchService friendlyMatchService;
    @Autowired private com.footballmanagergamesimulator.config.MatchEngineConfig engineConfig;
    @Autowired private InjuryTimelineService injuryTimelineService;
    @Autowired private MonthlyTrainingUpdateService monthlyTrainingUpdateService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.footballmanagergamesimulator.service.board.StaffLoyaltyService> staffLoyalty;
    @Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.footballmanagergamesimulator.service.board.DirectorWishService> directorWishes;
    @Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.footballmanagergamesimulator.service.board.FrontCoachService> frontCoach;
    @Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.footballmanagergamesimulator.service.board.BoardMeetingService> boardMeeting;

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
                processTrainingDay(calendar.getSeason(), calendar.getCurrentDay());
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
                // One conference for every human team that plays in this competition/round (design
                // DESIGN_MEDIA_SI_COMPETITII §1); each manager is shown only his own. A team plays one match a day,
                // so it never gets two pre-match conferences on the same day.
                long pressCompId = event.getCompetitionId() != null ? event.getCompetitionId() : 0;
                Set<Long> humanCompIds = getHumanTeamCompetitionIds(calendar.getSeason());

                if (pressCompId > 0 && !humanCompIds.contains(pressCompId)) {
                    result.put("details", "Press conference skipped (not in competition)");
                    result.put("userRelevant", false);
                    break;
                }

                List<Long> attending = new ArrayList<>();
                for (long htId : userContext.getAllHumanTeamIds()) {
                    // Check if manager has delegated press conferences to assistant
                    List<Human> humanManagers = humanRepository.findAllByTeamIdAndTypeId(htId, TypeNames.MANAGER_TYPE);
                    Human currentManager = resolveCurrentManager(htId, humanManagers);
                    if (currentManager != null
                            && (!currentManager.isAttendPressConferences() || currentManager.isAlwaysContinue())) {
                        continue;
                    }
                    attending.add(htId);
                }

                List<PressConference> conferences = pressConferenceService.generatePreMatchPressConferences(
                        attending, pressCompId, event.getMatchday(), event.getLegNumber(), calendar.getSeason());
                if (conferences.isEmpty()) {
                    result.put("details", "Press conference skipped (team not playing in this round)");
                    result.put("userRelevant", false);
                    break;
                }

                List<Map<String, Object>> conferenceViews = new ArrayList<>();
                for (PressConference pc : conferences) {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("teamId", pc.getTeamId());
                    view.put("pressConferenceId", pc.getId());
                    view.put("question", pressConferenceService.questionFor(pc));
                    conferenceViews.add(view);
                }
                result.put("details", "Press conference available");
                result.put("pressConferences", conferenceViews);
                // Older frontends read the first conference only.
                result.put("pressConferenceId", conferences.get(0).getId());
                result.put("pressConferenceQuestion", pressConferenceService.questionFor(conferences.get(0)));
                result.put("awaitingInput", true);
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
                // Recovery and daily maintenance already run once at the MORNING
                // boundary in GameAdvanceService. Older calendars may still contain
                // this compatibility event, but it must not repeat the whole market,
                // scouting and facility pass on the same day.
                result.put("details", "Daily injury update already processed");
                break;
            case "EUROPEAN_DRAW":
                if (event.getCompetitionId() == null || event.getMatchday() <= 0) {
                    result.put("details", "European draw skipped: missing competition data");
                    break;
                }
                result.putAll(europeanDrawService.executeDraw(event, calendar));
                competitionExpectationService.captureMissingBeforeMatches(calendar.getSeason());
                break;
            case "MATCH_LEAGUE":
            case "MATCH_CUP":
            case "MATCH_EUROPEAN":
                if (event.getCompetitionId() != null && event.getMatchday() > 0) {
                    Integer leg = event.getLegNumber() == 0 ? null : event.getLegNumber();
                    matchSimulationOrchestrator.simulateMatchday(
                            event.getCompetitionId(), event.getMatchday(), event.getSeason(), leg);
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
            long wagesPaid = chairmanModeProperties.isEnabled()
                    ? personalPayrollService.processTeam(team, season, currentDay)
                    : financeService.processTeamMonthlyWages(team, season, currentDay);

            // Process monthly merchandising income for all teams
            financeService.processMerchandisingIncome(team.getId(), season, currentDay);

            // Process debt interest if applicable
            financeService.processDebtInterest(team.getId(), season, currentDay);

            // Check if finances went negative -> create debt
            financeService.checkAndCreateDebt(team.getId());

            // Credit the team's manager their monthly salary into career earnings.
            if (!chairmanModeProperties.isEnabled()) accrueManagerSalary(team);

            // Send financial report to human managers
            if (userContext.isHumanTeam(team.getId())) {
                financeService.sendMonthlyFinancialReport(team.getId(), season, currentDay, wagesPaid);
                financialTroubleMediaService.publishIfNeeded(team.getId(), season, currentDay, wagesPaid);
            }
        }
        // An ambitious director works on the staff every month (design DESIGN_START_CARIERA §5.2).
        com.footballmanagergamesimulator.service.board.StaffLoyaltyService loyalty = staffLoyalty == null ? null : staffLoyalty.getIfAvailable();
        if (loyalty != null) loyalty.monthPassed();
        // The director passes on the chairman's wishes, or his own (design DESIGN_START_CARIERA §5).
        com.footballmanagergamesimulator.service.board.DirectorWishService wishes = directorWishes == null ? null : directorWishes.getIfAvailable();
        if (wishes != null) wishes.monthPassed();
        com.footballmanagergamesimulator.service.board.FrontCoachService front = frontCoach == null ? null : frontCoach.getIfAvailable();
        if (front != null) front.monthPassed();
        com.footballmanagergamesimulator.service.board.BoardMeetingService meeting = boardMeeting == null ? null : boardMeeting.getIfAvailable();
        if (meeting != null) meeting.monthPassed();
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
     * A training day: human teams train (their counters grow); then every league whose monthly
     * update is due today gets it — human teams from their counters, AI teams from their
     * coach's play — and is marked updated for this month.
     */
    private void processTrainingDay(int season, int day) {
        Set<Long> dueLeagues = monthlyTrainingUpdateService.dueLeagues(season, day);
        Map<Long, Long> leagueByTeam = dueLeagues.isEmpty() ? Map.of() : monthlyTrainingUpdateService.leagueByTeam();
        Random random = new Random();

        Set<Long> humanTeams = new HashSet<>(userContext.getAllHumanTeamIds());
        for (long teamId : humanTeams) {
            trainingService.processTrainingSession(teamId, season, day);
            if (dueLeagues.contains(leagueByTeam.get(teamId))) {
                monthlyTrainingUpdateService.updateHumanTeam(teamId, season, day, random);
            }
        }
        if (dueLeagues.isEmpty()) return;

        List<Long> dueAiTeams = leagueByTeam.entrySet().stream()
                .filter(entry -> !humanTeams.contains(entry.getKey()) && !userContext.isHumanTeam(entry.getKey()))
                .filter(entry -> dueLeagues.contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        monthlyTrainingUpdateService.updateAiTeams(dueAiTeams, season, random);
        monthlyTrainingUpdateService.markUpdated(dueLeagues, season, day);
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
        transferInstallmentService.payDue(season);

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
        long transitionStarted = System.nanoTime();
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
        long setupCommitted = System.nanoTime();

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
        long calendarGenerated = System.nanoTime();

        // Invalidate cached competition IDs since season changed
        invalidateHumanCompetitionsCache();

        // The product calendar contains routine events through day 365, while
        // the successful season transition happens on day 360. Those remaining
        // rows can never be reached after the client switches to newSeason, so
        // archive them explicitly instead of leaving retryable PENDING debris.
        int skippedEvents = calendarEventRepository.skipPendingEventsAfter(season, 360);
        long transitionDone = System.nanoTime();
        System.out.printf("=== SEASON_TRANSITION timing season=%d total=%dms setup_with_commit=%dms calendar_and_publish=%dms cleanup=%dms ===%n",
                season,
                (transitionDone - transitionStarted) / 1_000_000,
                (setupCommitted - transitionStarted) / 1_000_000,
                (calendarGenerated - setupCommitted) / 1_000_000,
                (transitionDone - calendarGenerated) / 1_000_000);

        System.out.println("=== Season transition complete. New season " + newSeason + " started. ===");
        if (skippedEvents > 0) {
            System.out.println("=== Archived " + skippedEvents
                    + " unreachable calendar events after season " + season + " transition. ===");
        }
    }
}
