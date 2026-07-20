package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.controller.AdminController;
import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.PressConference;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Matchday batch processing: simulates ALL match events for a day/phase in one pass,
 * aggregates human-team match results, processes suspensions per competition,
 * generates inbox news per competition, and detects/handles live-match sessions.
 *
 * <p>Extracted from {@link GameAdvanceService} as part of §6.5b. The advance loop
 * calls into here once per phase whenever it sees ≥1 match event.
 */
@Service
public class MatchdayBatchProcessor {

    @Autowired @Lazy private MatchSimulationOrchestrator matchSimulationOrchestrator;
    @Autowired private UserContext userContext;
    @Autowired @Lazy private SuspensionService suspensionService;
    @Autowired @Lazy private PressConferenceService pressConferenceService;
    @Autowired private LiveMatchSimulationService liveMatchSimulationService;
    @Autowired private HumanRepository humanRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ManagerInboxRepository managerInboxRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired private CompetitionFormatConfig competitionFormat;
    @Autowired @Lazy private ManagerCareerService managerCareerService;
    @Autowired @Lazy private AdminController adminController;

    @Value("${simulation.matchday.parallel.enabled:true}")
    private boolean parallelEnabled;

    @Value("${simulation.matchday.parallel.workers:4}")
    private int configuredWorkers;

    private ExecutorService simulationExecutor;
    private int workerCount;

    @PostConstruct
    void initialiseExecutor() {
        workerCount = Math.max(1, configuredWorkers);
        if (!parallelEnabled || workerCount == 1) return;

        AtomicInteger sequence = new AtomicInteger();
        simulationExecutor = Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable, "match-sim-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        });
        System.out.println("=== Matchday parallel simulation enabled: " + workerCount + " workers ===");
    }

    @PreDestroy
    void shutdownExecutor() {
        if (simulationExecutor == null) return;
        simulationExecutor.shutdown();
        try {
            if (!simulationExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                simulationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            simulationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Batch-process all match events for a single phase.
     * Simulates all competitions at once instead of one-by-one.
     */
    public Map<String, Object> processBatchMatches(List<CalendarEvent> matchEvents, GameCalendar calendar) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "MATCH_DAY");
        result.put("day", calendar.getCurrentDay());

        List<String> matchSummaries = new ArrayList<>();

        // Find which competition the human team plays in (to fetch result after simulation)
        Long humanCompetitionId = null;
        int humanMatchday = 0;

        // Simulate independent competitions concurrently. Competitions that share
        // a team are placed in different waves, because both would otherwise update
        // the same players/fitness/morale rows. European competitions are also kept
        // on one lane because they share draw/drop/coefficient state.
        long _tBatchStart = System.nanoTime();
        List<SimulationPlan> plans = buildSimulationPlans(matchEvents);
        if (simulationExecutor == null || plans.size() < 2) {
            for (SimulationPlan plan : plans) simulate(plan.event());
        } else {
            simulateInParallelWaves(plans);
        }
        plans.stream().map(SimulationPlan::event).map(CalendarEvent::getTitle)
                .forEach(matchSummaries::add);
        long batchMs = (System.nanoTime() - _tBatchStart) / 1_000_000;
        System.out.println(String.format(
                "=== processBatchMatches: %d competitions in %dms (parallel=%s, workers=%d) ===",
                plans.size(), batchMs, simulationExecutor != null, workerCount));

        // A ban applies to this match, then one match is served, and only after
        // that do cards from the match create bans for the next fixture. Doing
        // this once per actually-played fixture keeps Continue and Fast Forward
        // on the exact same discipline path.
        processDisciplineForPlayedFixtures(matchEvents, calendar);

        // Mid-season manager reviews are meaningful only after league tables
        // changed. Running them here replaces the old every-calendar-day scan.
        evaluateManagersAfterLeagueMatchdays(plans, calendar.getSeason());

        // AFTER all simulations: find match results for ALL human teams
        List<Long> htIds = userContext.getAllHumanTeamIds();
        Map<Long, Map<String, Object>> allMatchResults = new LinkedHashMap<>();

        for (long htId : htIds) {
            for (CalendarEvent event : matchEvents) {
                if (event.getCompetitionId() == null || event.getMatchday() <= 0) continue;
                Map<String, Object> mr = matchSimulationOrchestrator.getHumanMatchResult(
                        event.getCompetitionId(), event.getMatchday(), event.getSeason(), htId);
                if (mr.containsKey("score")) {
                    allMatchResults.put(htId, mr);
                    if (humanMatchday == 0) {
                        humanCompetitionId = event.getCompetitionId();
                        humanMatchday = event.getMatchday();
                    }
                    break;
                }
            }
        }

        // Generate inbox news with other match results
        generateMatchDayNews(matchEvents, calendar);

        result.put("title", "Match Day - " + matchSummaries.size() + " competitions");
        result.put("details", String.join(", ", matchSummaries));

        if (!allMatchResults.isEmpty()) {
            result.put("allMatchResults", allMatchResults);
            // Backward compat: also put first result as "matchResult"
            result.put("matchResult", allMatchResults.values().iterator().next());
        }

        // Check for a live match session belonging to the human user. We
        // look this up DIRECTLY on the simulation service (instead of via
        // allMatchResults) because interactive sessions don't write the
        // CompetitionTeamInfoDetail row yet — that happens on /commit —
        // so the user's match wouldn't show up in allMatchResults at all.
        for (long htId : htIds) {
            for (CalendarEvent event : matchEvents) {
                if (event.getCompetitionId() == null || event.getMatchday() <= 0) continue;
                var session = liveMatchSimulationService.findSessionForTeam(
                        event.getCompetitionId(), event.getSeason(), event.getMatchday(), htId);
                if (session == null || session.isCommitted()) continue;

                String liveKey = LiveMatchSimulationService.buildKey(
                        session.getCompetitionId(), session.getSeason(), session.getRound(),
                        session.getTeamId1(), session.getTeamId2());
                result.put("hasLiveMatch", true);
                result.put("liveMatchKey", liveKey);
                boolean interactive = !session.isFinished();
                result.put("liveMatchInteractive", interactive);

                // For legacy (engine already finished sync — currently no path
                // produces this since Session 4) schedule the post-match PC
                // inline. Interactive matches generate the PC on /commit.
                if (!interactive) {
                    var liveData = liveMatchSimulationService.getLiveMatchData(liveKey);
                    List<Human> humanManagers = humanRepository.findAllByTeamIdAndTypeId(htId, TypeNames.MANAGER_TYPE);
                    Human currentManager = resolveCurrentManager(htId, humanManagers);
                    if (liveData != null && currentManager != null
                            && currentManager.isViewFullMatch()
                            && currentManager.isAttendPressConferences()
                            && !currentManager.isAlwaysContinue()) {
                        boolean isHome = liveData.getHomeTeamId() == htId;
                        int teamScore = isHome ? liveData.getHomeScore() : liveData.getAwayScore();
                        int opponentScore = isHome ? liveData.getAwayScore() : liveData.getHomeScore();
                        PressConference postMatchPc = pressConferenceService.generatePostMatchPressConference(
                                htId, event.getCompetitionId(), event.getMatchday(),
                                calendar.getSeason(), teamScore, opponentScore);
                        result.put("postMatchPressConferenceId", postMatchPc.getId());
                        result.put("postMatchPressConferenceOutcome",
                                teamScore > opponentScore ? "WIN"
                                        : teamScore < opponentScore ? "LOSS" : "DRAW");
                    }
                }
                break;
            }
            if (result.containsKey("hasLiveMatch")) break;
        }

        return result;
    }

    private Human resolveCurrentManager(long teamId, List<Human> managers) {
        if (managers.isEmpty()) return null;
        Long linkedManagerId = userRepository.findAllByTeamId(teamId).stream()
                .map(User::getManagerId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (linkedManagerId == null) return managers.get(0);
        return managers.stream()
                .filter(manager -> manager.getId() == linkedManagerId)
                .findFirst()
                .orElse(managers.get(0));
    }

    private void simulate(CalendarEvent event) {
        Integer leg = event.getLegNumber() == 0 ? null : event.getLegNumber();
        matchSimulationOrchestrator.simulateMatchday(
                event.getCompetitionId(), event.getMatchday(), event.getSeason(), leg);
    }

    private void processDisciplineForPlayedFixtures(
            List<CalendarEvent> matchEvents, GameCalendar calendar) {
        if (suspensionService.isAvailabilityDisabled()) return;

        Set<String> processedEvents = new HashSet<>();
        for (CalendarEvent event : matchEvents) {
            if (event.getCompetitionId() == null || event.getMatchday() <= 0) continue;
            Competition competition = competitionRepository.findById(event.getCompetitionId()).orElse(null);
            if (competition == null) continue;

            int round = competitionFormat.get((int) competition.getTypeId())
                    .roundForMatchday(event.getMatchday());
            String eventKey = event.getCompetitionId() + ":" + event.getSeason()
                    + ":" + round + ":" + event.getLegNumber();
            if (!processedEvents.add(eventKey)) continue;

            List<com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch> playedFixtures = competitionTeamInfoMatchRepository
                    .findAllByCompetitionIdAndRoundAndSeasonNumber(
                            event.getCompetitionId(), round, String.valueOf(event.getSeason()))
                    .stream()
                    .filter(match -> event.getLegNumber() == 0
                            || match.getLegNumber() == event.getLegNumber())
                    .filter(match -> match.getTeam1Score() >= 0 && match.getTeam2Score() >= 0)
                    .toList();

            playedFixtures.forEach(fixture ->
                    suspensionService.processPlayedFixture(fixture, event.getSeason()));
        }
    }

    private List<SimulationPlan> buildSimulationPlans(List<CalendarEvent> matchEvents) {
        List<CalendarEvent> validEvents = matchEvents.stream()
                .filter(event -> event.getCompetitionId() != null && event.getMatchday() > 0)
                .toList();
        if (validEvents.isEmpty()) return List.of();

        Set<Long> competitionIds = validEvents.stream()
                .map(CalendarEvent::getCompetitionId)
                .collect(Collectors.toSet());
        Map<Long, Competition> competitions = competitionRepository.findAllById(competitionIds).stream()
                .collect(Collectors.toMap(Competition::getId, competition -> competition));

        Map<CompetitionSeason, Set<Long>> participantTeams = new LinkedHashMap<>();
        for (CalendarEvent event : validEvents) {
            CompetitionSeason key = new CompetitionSeason(event.getCompetitionId(), event.getSeason());
            participantTeams.computeIfAbsent(key, ignored -> competitionTeamInfoRepository
                    .findAllByCompetitionIdAndSeasonNumber(event.getCompetitionId(), event.getSeason())
                    .stream()
                    .map(CompetitionTeamInfo::getTeamId)
                    .filter(teamId -> teamId > 0)
                    .collect(Collectors.toSet()));
        }

        List<SimulationPlan> plans = new ArrayList<>(validEvents.size());
        for (CalendarEvent event : validEvents) {
            Competition competition = competitions.get(event.getCompetitionId());
            int typeId = competition == null ? 0 : (int) competition.getTypeId();
            int round = competitionFormat.get(typeId).roundForMatchday(event.getMatchday());

            Set<Long> teamIds = competitionTeamInfoMatchRepository
                    .findAllByCompetitionIdAndRoundAndSeasonNumber(
                            event.getCompetitionId(), round, String.valueOf(event.getSeason()))
                    .stream()
                    .filter(match -> event.getLegNumber() == 0
                            || match.getLegNumber() == event.getLegNumber())
                    .flatMap(match -> java.util.stream.Stream.of(match.getTeam1Id(), match.getTeam2Id()))
                    .filter(teamId -> teamId > 0)
                    .collect(Collectors.toCollection(HashSet::new));

            // A draw can create the actual fixtures inside simulateMatchday. The
            // participant list lets the planner still detect conflicts beforehand.
            teamIds.addAll(participantTeams.getOrDefault(
                    new CompetitionSeason(event.getCompetitionId(), event.getSeason()), Set.of()));
            plans.add(new SimulationPlan(event, Set.copyOf(teamIds), typeId));
        }
        return plans;
    }

    void evaluateManagersAfterLeagueMatchdays(List<SimulationPlan> plans, int season) {
        if (!adminController.areJobOffersEnabled()) return;
        Set<Long> playedLeagueCompetitionIds = plans.stream()
                .filter(plan -> plan.competitionTypeId() == 1 || plan.competitionTypeId() == 3)
                .map(plan -> plan.event().getCompetitionId())
                .collect(Collectors.toSet());
        if (!playedLeagueCompetitionIds.isEmpty()) {
            managerCareerService.evaluateMidSeasonSackings(season, playedLeagueCompetitionIds);
        }
    }

    private void simulateInParallelWaves(List<SimulationPlan> plans) {
        List<List<SimulationPlan>> waves = buildExecutionWaves(plans, workerCount);
        for (int waveIndex = 0; waveIndex < waves.size(); waveIndex++) {
            List<SimulationPlan> wave = waves.get(waveIndex);
            long waveStart = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>(wave.size());
            for (SimulationPlan plan : wave) {
                futures.add(simulationExecutor.submit(() -> simulate(plan.event())));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    futures.forEach(pending -> pending.cancel(true));
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Matchday simulation interrupted", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException runtimeException) throw runtimeException;
                    throw new IllegalStateException("Competition simulation failed", cause);
                }
            }
            long waveMs = (System.nanoTime() - waveStart) / 1_000_000;
            String competitions = wave.stream()
                    .map(plan -> String.valueOf(plan.event().getCompetitionId()))
                    .collect(Collectors.joining(","));
            System.out.println("  [parallel wave " + (waveIndex + 1) + "/" + waves.size()
                    + "] competitions=" + competitions + " duration=" + waveMs + "ms");
        }
    }

    /**
     * Greedy wave planner. Within one wave all team sets are disjoint and at
     * most one European competition is present. Package-private for focused tests.
     */
    static List<List<SimulationPlan>> buildExecutionWaves(List<SimulationPlan> plans, int workers) {
        int maxWaveSize = Math.max(1, workers);
        List<SimulationPlan> remaining = new ArrayList<>(plans);
        List<List<SimulationPlan>> waves = new ArrayList<>();

        while (!remaining.isEmpty()) {
            List<SimulationPlan> wave = new ArrayList<>();
            Set<Long> usedTeams = new HashSet<>();
            boolean hasEuropeanCompetition = false;

            Iterator<SimulationPlan> iterator = remaining.iterator();
            while (iterator.hasNext() && wave.size() < maxWaveSize) {
                SimulationPlan candidate = iterator.next();
                boolean teamConflict = candidate.teamIds().stream().anyMatch(usedTeams::contains);
                boolean europeanConflict = candidate.european() && hasEuropeanCompetition;
                if (teamConflict || europeanConflict) continue;

                wave.add(candidate);
                usedTeams.addAll(candidate.teamIds());
                hasEuropeanCompetition |= candidate.european();
                iterator.remove();
            }

            // The first remaining item can always start a fresh wave, but keep a
            // defensive fallback so a future planner rule cannot cause a spin loop.
            if (wave.isEmpty()) wave.add(remaining.remove(0));
            waves.add(List.copyOf(wave));
        }
        return List.copyOf(waves);
    }

    record SimulationPlan(CalendarEvent event, Set<Long> teamIds, int competitionTypeId) {
        SimulationPlan {
            teamIds = Set.copyOf(teamIds);
        }

        boolean european() {
            return competitionTypeId == 4 || competitionTypeId == 5;
        }
    }

    private record CompetitionSeason(long competitionId, int season) {}

    /**
     * Build per-competition inbox messages summarising the other-team match results
     * for the day. One inbox row per (competition × human user).
     */
    private void generateMatchDayNews(List<CalendarEvent> matchEvents, GameCalendar calendar) {
        List<Long> humanTeamIds = userContext.getAllHumanTeamIds();

        // Group results by competition so each competition gets its own inbox message
        Map<String, StringBuilder> resultsByCompetition = new LinkedHashMap<>();

        for (CalendarEvent event : matchEvents) {
            if (event.getCompetitionId() == null || event.getMatchday() <= 0) continue;
            List<Map<String, Object>> otherResults = matchSimulationOrchestrator.getAllMatchResults(
                    event.getCompetitionId(), event.getMatchday(), event.getSeason());
            if (otherResults.isEmpty()) continue;

            String competitionName = (String) otherResults.get(0).get("competitionName");
            StringBuilder sb = resultsByCompetition.computeIfAbsent(competitionName, k -> new StringBuilder());
            for (Map<String, Object> mr : otherResults) {
                sb.append(mr.get("team1Name")).append(" ")
                        .append(mr.get("score")).append(" ")
                        .append(mr.get("team2Name")).append("\n");
            }
        }

        for (Map.Entry<String, StringBuilder> entry : resultsByCompetition.entrySet()) {
            String content = entry.getValue().toString().trim();
            if (content.isEmpty()) continue;

            for (long htId : humanTeamIds) {
                ManagerInbox inbox = new ManagerInbox();
                inbox.setTeamId(htId);
                inbox.setSeasonNumber(calendar.getSeason());
                inbox.setRoundNumber(calendar.getCurrentDay());
                inbox.setTitle("Match Day Results - " + entry.getKey());
                inbox.setContent(content);
                inbox.setCategory("league_news");
                inbox.setRead(false);
                inbox.setCreatedAt(System.currentTimeMillis());
                managerInboxRepository.save(inbox);
            }
        }
    }
}
