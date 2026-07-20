package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.GameplayFeatureConfig;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.MatchEvent;
import com.footballmanagergamesimulator.model.Suspension;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.MatchEventRepository;
import com.footballmanagergamesimulator.repository.SuspensionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service responsible for the player suspension/discipline system.
 * Handles processing of cards from match events, creating suspensions,
 * tracking accumulated yellow cards, and serving suspensions across matchdays.
 */
@Service
public class SuspensionService {

    private static final int YELLOW_CARD_ACCUMULATION = 5; // 5 yellows = 1 match ban

    @Autowired
    private UserContext userContext;

    @Autowired
    private SuspensionRepository suspensionRepository;
    @Autowired
    private MatchEventRepository matchEventRepository;
    @Autowired
    private ManagerInboxRepository managerInboxRepository;
    @Autowired
    private CompetitionTeamInfoMatchRepository fixtureRepository;
    @Autowired(required = false)
    private GameplayFeatureConfig gameplayFeatures;

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
        if (competitionId == null || isAvailabilityDisabled()) return;

        List<MatchEvent> events = matchEventRepository
                .findAllByCompetitionIdAndSeasonNumberAndRoundNumber(competitionId, season, matchday);

        processEvents(events, competitionId, season);
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
        if (isAvailabilityDisabled()) return List.of();
        List<Suspension> newSuspensions = new ArrayList<>();

        List<MatchEvent> events = matchEventRepository
                .findAllByCompetitionIdAndSeasonNumberAndRoundNumberAndTeamId1AndTeamId2(
                        competitionId, season, roundNumber, team1Id, team2Id);

        newSuspensions.addAll(processEvents(events, competitionId, season));
        return newSuspensions;
    }

    /**
     * Persist bans exactly once. Yellow-card bans are derived from the number of
     * completed five-card milestones, rather than from the final total modulo
     * five; this also works when a fast-forward chunk contains several rounds.
     */
    private List<Suspension> processEvents(List<MatchEvent> events, long competitionId, int season) {
        List<Suspension> created = new ArrayList<>();

        for (MatchEvent event : events) {
            if (!"red_card".equals(event.getEventType())) continue;
            if (event.getId() > 0
                    && suspensionRepository.existsBySourceMatchEventIdAndReason(event.getId(), "RED_CARD")) {
                continue;
            }
            Suspension suspension = createRedCardSuspension(event, competitionId, season);
            created.add(suspension);
            notifyIfHuman(suspension);
        }

        Map<Long, List<MatchEvent>> yellowsByPlayer = events.stream()
                .filter(event -> "yellow_card".equals(event.getEventType()))
                .collect(Collectors.groupingBy(MatchEvent::getPlayerId));
        for (List<MatchEvent> playerEvents : yellowsByPlayer.values()) {
            MatchEvent source = playerEvents.stream()
                    .max(Comparator.comparingLong(MatchEvent::getId))
                    .orElseThrow();
            long completedMilestones = countYellowCards(source.getPlayerId(), competitionId, season)
                    / YELLOW_CARD_ACCUMULATION;
            long existingBans = suspensionRepository
                    .sumMatchesBannedByPlayerAndCompetitionAndSeasonAndReason(
                            source.getPlayerId(), competitionId, season, "ACCUMULATED_YELLOWS");
            int missingBans = (int) Math.max(0, completedMilestones - existingBans);
            if (missingBans == 0) continue;

            // A legacy/large fast-forward save may have skipped more than one
            // milestone. Represent the debt as one row, avoiding duplicate UI cards.
            Suspension suspension = createYellowAccumulationSuspension(
                    source, competitionId, season, missingBans);
            created.add(suspension);
            notifyIfHuman(suspension);
        }
        return created;
    }

    private void notifyIfHuman(Suspension suspension) {
        if (userContext.isHumanTeam(suspension.getTeamId())) {
            sendSuspensionNotification(suspension);
        }
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
        suspension.setSourceMatchEventId(event.getId());
        suspension.setActive(true);
        return suspensionRepository.save(suspension);
    }

    /**
     * Create a 1-match suspension for accumulated yellow cards.
     */
    private Suspension createYellowAccumulationSuspension(
            MatchEvent event, long competitionId, int season, int matchesBanned) {
        Suspension suspension = new Suspension();
        suspension.setPlayerId(event.getPlayerId());
        suspension.setPlayerName(event.getPlayerName());
        suspension.setTeamId(event.getTeamId());
        suspension.setCompetitionId(competitionId);
        suspension.setMatchesBanned(matchesBanned);
        suspension.setMatchesServed(0);
        suspension.setReason("ACCUMULATED_YELLOWS");
        suspension.setSeasonNumber(season);
        suspension.setDayIssued(0);
        suspension.setSourceMatchEventId(event.getId());
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
        if (isAvailabilityDisabled()) return;
        List<Suspension> active = suspensionRepository
                .findAllByTeamIdAndCompetitionIdAndActive(teamId, competitionId, true);

        Set<String> servedNotifications = new HashSet<>();
        for (Suspension s : active) {
            s.setMatchesServed(s.getMatchesServed() + 1);
            if (s.getMatchesServed() >= s.getMatchesBanned()) {
                s.setActive(false);

                // Notify human team when suspension is served
                String notificationKey = s.getPlayerId() + ":" + s.getReason();
                if (userContext.isHumanTeam(s.getTeamId())
                        && servedNotifications.add(notificationKey)) {
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
        if (isAvailabilityDisabled()) return List.of();
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
        if (isAvailabilityDisabled()) return List.of();
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
        if (isAvailabilityDisabled()) return List.of();
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
        if (isAvailabilityDisabled()) return false;
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
        if (isAvailabilityDisabled()) return 0;
        return countYellowCards(playerId, competitionId, season);
    }

    public boolean isAvailabilityDisabled() {
        return gameplayFeatures != null && gameplayFeatures.isPlayerAvailabilityDisabled();
    }

    /**
     * Complete the discipline side-effects for exactly one played fixture.
     * The persisted guard makes both normal Continue and Fast Forward safe to
     * retry without serving a ban twice or processing the same cards twice.
     */
    @Transactional
    public void processPlayedFixture(CompetitionTeamInfoMatch fixture, int season) {
        // Keep the disabled feature genuinely free: no discipline queries and no
        // per-fixture UPDATE merely to mark a side-effect that is turned off.
        if (isAvailabilityDisabled()) return;
        if (fixture == null || fixture.isDisciplineProcessed()
                || fixture.getTeam1Score() < 0 || fixture.getTeam2Score() < 0) return;
        serveMatchday(fixture.getTeam1Id(), fixture.getCompetitionId());
        serveMatchday(fixture.getTeam2Id(), fixture.getCompetitionId());
        processMatchCards(fixture.getCompetitionId(), season, (int) fixture.getRound(),
                fixture.getTeam1Id(), fixture.getTeam2Id());
        fixture.setDisciplineProcessed(true);
        fixtureRepository.save(fixture);
    }

    @Transactional
    public void processPlayedFixture(long competitionId, int season, int round,
                                     long team1Id, long team2Id, int legNumber) {
        fixtureRepository.findPlayedFixture(competitionId, round, String.valueOf(season),
                        team1Id, team2Id, legNumber).stream()
                .filter(fixture -> fixture.getTeam1Score() >= 0 && fixture.getTeam2Score() >= 0)
                .findFirst()
                .ifPresent(fixture -> processPlayedFixture(fixture, season));
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
        inbox.setTeamId(suspension.getTeamId());
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
        inbox.setTeamId(suspension.getTeamId());
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
