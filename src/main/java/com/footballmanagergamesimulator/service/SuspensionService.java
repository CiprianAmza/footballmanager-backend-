package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.Suspension;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.SuspensionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service responsible for the player suspension/discipline system.
 * Handles processing of cards from match events, creating suspensions,
 * tracking accumulated yellow cards, and serving suspensions across matchdays.
 */
@Service
public class SuspensionService {

    private static final long HUMAN_TEAM_ID = 1L;
    private static final int YELLOW_CARD_ACCUMULATION = 5; // 5 yellows = 1 match ban

    @Autowired
    private SuspensionRepository suspensionRepository;
    @Autowired
    private MatchEventRepository matchEventRepository;
    @Autowired
    private ManagerInboxRepository managerInboxRepository;

    // ==================== CARD PROCESSING ====================

    /**
     * Process all card events for a matchday across all teams in a competition.
     * This is a convenience method that delegates to the full processMatchCards method
     * by scanning match events to find the involved teams.
     *
     * @param competitionId the competition ID
     * @param matchday      the matchday number
     * @param season        the season number
     */
    public void processMatchCards(Long competitionId, int matchday, int season) {
        if (competitionId == null) return;

        List<MatchEvent> events = matchEventRepository
                .findAllByCompetitionIdAndSeasonNumberAndRoundNumber(competitionId, season, matchday);

        // Extract unique team pairs from the events
        Set<Long> processedTeams = new java.util.HashSet<>();
        for (MatchEvent event : events) {
            if (processedTeams.contains(event.getTeamId())) continue;

            if ("red_card".equals(event.getEventType()) || "yellow_card".equals(event.getEventType())) {
                long teamId = event.getTeamId();
                processedTeams.add(teamId);

                if ("red_card".equals(event.getEventType())) {
                    Suspension suspension = createRedCardSuspension(event, competitionId, season);
                    if (event.getTeamId() == HUMAN_TEAM_ID) {
                        sendSuspensionNotification(suspension);
                    }
                }

                if ("yellow_card".equals(event.getEventType())) {
                    long yellowCount = countYellowCards(event.getPlayerId(), competitionId, season);
                    if (yellowCount > 0 && yellowCount % YELLOW_CARD_ACCUMULATION == 0) {
                        Suspension suspension = createYellowAccumulationSuspension(event, competitionId, season);
                        if (event.getTeamId() == HUMAN_TEAM_ID) {
                            sendSuspensionNotification(suspension);
                        }
                    }
                }
            }
        }
    }

    /**
     * After a match, process all card events and create suspensions where appropriate.
     * A direct red card results in a 3-match ban.
     * Every 5th accumulated yellow card in a competition results in a 1-match ban.
     *
     * @param competitionId the competition ID
     * @param season        the season number
     * @param roundNumber   the round/matchday number
     * @param team1Id       home team ID
     * @param team2Id       away team ID
     * @return list of newly created suspensions
     */
    public List<Suspension> processMatchCards(long competitionId, int season, int roundNumber, long team1Id, long team2Id) {
        List<Suspension> newSuspensions = new ArrayList<>();

        List<MatchEvent> events = matchEventRepository
                .findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId1AndTeamId2(
                        competitionId, season, roundNumber, team1Id, team2Id);

        for (MatchEvent event : events) {
            if ("red_card".equals(event.getEventType())) {
                Suspension suspension = createRedCardSuspension(event, competitionId, season);
                newSuspensions.add(suspension);

                if (event.getTeamId() == HUMAN_TEAM_ID) {
                    sendSuspensionNotification(suspension);
                }
            }

            if ("yellow_card".equals(event.getEventType())) {
                long yellowCount = countYellowCards(event.getPlayerId(), competitionId, season);

                if (yellowCount > 0 && yellowCount % YELLOW_CARD_ACCUMULATION == 0) {
                    Suspension suspension = createYellowAccumulationSuspension(event, competitionId, season);
                    newSuspensions.add(suspension);

                    if (event.getTeamId() == HUMAN_TEAM_ID) {
                        sendSuspensionNotification(suspension);
                    }
                }
            }
        }

        return newSuspensions;
    }

    // ==================== SUSPENSION CREATION ====================

    /**
     * Create a 3-match suspension for a direct red card.
     */
    private Suspension createRedCardSuspension(MatchEvent event, long competitionId, int season) {
        Suspension suspension = new Suspension();
        suspension.setPlayerId(event.getPlayerId());
        suspension.setPlayerName(event.getPlayerName());
        suspension.setTeamId(event.getTeamId());
        suspension.setCompetitionId(competitionId);
        suspension.setMatchesBanned(3);
        suspension.setMatchesServed(0);
        suspension.setReason("RED_CARD");
        suspension.setSeasonNumber(season);
        suspension.setDayIssued(0); // Will be set from game calendar if available
        suspension.setActive(true);
        return suspensionRepository.save(suspension);
    }

    /**
     * Create a 1-match suspension for accumulated yellow cards.
     */
    private Suspension createYellowAccumulationSuspension(MatchEvent event, long competitionId, int season) {
        Suspension suspension = new Suspension();
        suspension.setPlayerId(event.getPlayerId());
        suspension.setPlayerName(event.getPlayerName());
        suspension.setTeamId(event.getTeamId());
        suspension.setCompetitionId(competitionId);
        suspension.setMatchesBanned(1);
        suspension.setMatchesServed(0);
        suspension.setReason("ACCUMULATED_YELLOWS");
        suspension.setSeasonNumber(season);
        suspension.setDayIssued(0);
        suspension.setActive(true);
        return suspensionRepository.save(suspension);
    }

    // ==================== SERVING SUSPENSIONS ====================

    /**
     * Serve a matchday for all active suspensions for a team in a competition.
     * Called before match simulation so suspended players are excluded from selection.
     * After serving, if a suspension has been fully served it is deactivated.
     *
     * @param teamId        the team ID
     * @param competitionId the competition ID
     */
    public void serveMatchday(long teamId, long competitionId) {
        List<Suspension> active = suspensionRepository
                .findAllByTeamIdAndCompetitionIdAndActive(teamId, competitionId, true);

        for (Suspension s : active) {
            s.setMatchesServed(s.getMatchesServed() + 1);
            if (s.getMatchesServed() >= s.getMatchesBanned()) {
                s.setActive(false);

                // Notify human team when suspension is served
                if (s.getTeamId() == HUMAN_TEAM_ID) {
                    sendSuspensionServedNotification(s);
                }
            }
            suspensionRepository.save(s);
        }
    }

    // ==================== QUERIES ====================

    /**
     * Get the list of player IDs who are currently suspended for a team in a given competition.
     *
     * @param teamId        the team ID
     * @param competitionId the competition ID
     * @return list of suspended player IDs
     */
    public List<Long> getSuspendedPlayerIds(long teamId, long competitionId) {
        return suspensionRepository
                .findAllByTeamIdAndCompetitionIdAndActive(teamId, competitionId, true)
                .stream()
                .map(Suspension::getPlayerId)
                .collect(Collectors.toList());
    }

    /**
     * Get all active suspensions for a specific player across all competitions.
     *
     * @param playerId the player ID
     * @return list of active suspensions
     */
    public List<Suspension> getActiveSuspensionsForPlayer(long playerId) {
        return suspensionRepository.findAllByPlayerIdAndActive(playerId, true);
    }

    /**
     * Get all active suspensions for a team in a specific competition.
     *
     * @param teamId        the team ID
     * @param competitionId the competition ID
     * @return list of active suspensions
     */
    public List<Suspension> getActiveSuspensionsForTeamInCompetition(long teamId, long competitionId) {
        return suspensionRepository.findAllByTeamIdAndCompetitionIdAndActive(teamId, competitionId, true);
    }

    /**
     * Check if a specific player is suspended in a given competition.
     *
     * @param playerId      the player ID
     * @param competitionId the competition ID
     * @return true if the player has an active suspension in this competition
     */
    public boolean isPlayerSuspended(long playerId, long competitionId) {
        List<Suspension> suspensions = suspensionRepository.findAllByPlayerIdAndActive(playerId, true);
        return suspensions.stream().anyMatch(s -> s.getCompetitionId() == competitionId);
    }

    /**
     * Get the total number of yellow cards a player has accumulated in a competition this season.
     *
     * @param playerId      the player ID
     * @param competitionId the competition ID
     * @param season        the season number
     * @return number of yellow cards
     */
    public long getYellowCardCount(long playerId, long competitionId, int season) {
        return countYellowCards(playerId, competitionId, season);
    }

    // ==================== PRIVATE HELPERS ====================

    /**
     * Count yellow card events for a player in a specific competition and season.
     * Uses the MatchEvent repository to scan all match events.
     *
     * Note: For better performance at scale, a dedicated query method should be added
     * to MatchEventRepository:
     * long countByPlayerIdAndCompetitionIdAndSeasonNumberAndEventType(
     *     long playerId, long competitionId, int seasonNumber, String eventType);
     */
    private long countYellowCards(long playerId, long competitionId, int season) {
        return matchEventRepository.countByPlayerIdAndCompetitionIdAndSeasonNumberAndEventType(
                playerId, competitionId, season, "yellow_card");
    }

    /**
     * Send an inbox notification to the human manager about a new suspension.
     */
    private void sendSuspensionNotification(Suspension suspension) {
        String reasonText;
        switch (suspension.getReason()) {
            case "RED_CARD":
                reasonText = "a red card";
                break;
            case "ACCUMULATED_YELLOWS":
                reasonText = "accumulating " + YELLOW_CARD_ACCUMULATION + " yellow cards";
                break;
            case "VIOLENT_CONDUCT":
                reasonText = "violent conduct";
                break;
            default:
                reasonText = suspension.getReason().replace("_", " ").toLowerCase();
        }

        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(HUMAN_TEAM_ID);
        inbox.setSeasonNumber(suspension.getSeasonNumber());
        inbox.setRoundNumber(0);
        inbox.setTitle("Player Suspended: " + suspension.getPlayerName());
        inbox.setContent(suspension.getPlayerName() + " has been suspended for "
                + suspension.getMatchesBanned() + " match(es) due to " + reasonText + ".");
        inbox.setCategory("discipline");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);
    }

    /**
     * Send an inbox notification when a suspension has been fully served.
     */
    private void sendSuspensionServedNotification(Suspension suspension) {
        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(HUMAN_TEAM_ID);
        inbox.setSeasonNumber(suspension.getSeasonNumber());
        inbox.setRoundNumber(0);
        inbox.setTitle("Suspension Served: " + suspension.getPlayerName());
        inbox.setContent(suspension.getPlayerName() + " has served their "
                + suspension.getMatchesBanned() + "-match suspension and is now available for selection.");
        inbox.setCategory("discipline");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);
    }
}
