package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.PressConference;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    @Autowired private ManagerInboxRepository managerInboxRepository;

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

        // Simulate ALL competitions (just simulateRound, same as old play())
        long _tBatchStart = System.nanoTime();
        for (CalendarEvent event : matchEvents) {
            if (event.getCompetitionId() != null && event.getMatchday() > 0) {
                Integer leg = event.getLegNumber() == 0 ? null : event.getLegNumber();
                matchSimulationOrchestrator.simulateMatchday(
                        event.getCompetitionId(), event.getMatchday(), event.getSeason(), leg);
                matchSummaries.add(event.getTitle());
            }
        }
        long batchMs = (System.nanoTime() - _tBatchStart) / 1_000_000;
        System.out.println(String.format(
                "=== processBatchMatches: %d competitions in %dms (avg %dms/comp) ===",
                matchEvents.size(), batchMs, matchEvents.isEmpty() ? 0 : batchMs / matchEvents.size()));

        // AFTER all simulations: find match results for ALL human teams
        List<Long> htIds = userContext.getAllHumanTeamIds();
        Map<Long, Map<String, Object>> allMatchResults = new LinkedHashMap<>();
        Set<Long> humanCompetitionIds = new HashSet<>();

        for (long htId : htIds) {
            for (CalendarEvent event : matchEvents) {
                if (event.getCompetitionId() == null || event.getMatchday() <= 0) continue;
                Map<String, Object> mr = matchSimulationOrchestrator.getHumanMatchResult(
                        event.getCompetitionId(), event.getMatchday(), event.getSeason(), htId);
                if (mr.containsKey("score")) {
                    allMatchResults.put(htId, mr);
                    humanCompetitionIds.add(event.getCompetitionId());
                    if (humanMatchday == 0) {
                        humanCompetitionId = event.getCompetitionId();
                        humanMatchday = event.getMatchday();
                    }
                    break;
                }
            }
        }

        // Process suspensions for all competitions where human teams played
        for (Long compId : humanCompetitionIds) {
            for (CalendarEvent event : matchEvents) {
                if (compId.equals(event.getCompetitionId()) && event.getMatchday() > 0) {
                    suspensionService.processMatchCards(compId, event.getMatchday(), calendar.getSeason());
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
                    if (liveData != null && !humanManagers.isEmpty()
                            && humanManagers.get(0).isViewFullMatch()
                            && humanManagers.get(0).isAttendPressConferences()) {
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
