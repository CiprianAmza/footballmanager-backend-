package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.NationalTeamCallup;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.InjuryRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.NationalTeamCallupRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Service responsible for managing national team callups during international breaks.
 * Handles selecting top-rated players, processing injuries during duty,
 * morale boosts, and returning players to their clubs.
 */
@Service
public class NationalTeamService {

    private static final double MIN_RATING_FOR_CALLUP = 70.0;
    private static final double INJURY_CHANCE = 0.10; // 10%
    private static final double MORALE_BOOST_CHANCE = 0.05; // 5%
    private static final int INTERNATIONAL_BREAK_DURATION = 14; // days

    @Autowired
    private UserContext userContext;

    @Autowired
    private NationalTeamCallupRepository nationalTeamCallupRepository;
    @Autowired
    private HumanRepository humanRepository;
    @Autowired
    private InjuryRepository injuryRepository;
    @Autowired
    private ManagerInboxRepository managerInboxRepository;

    private final Random random = new Random();

    // ==================== INTERNATIONAL BREAK ====================

    /**
     * Process an international break. Called on NATIONAL_TEAM_CALL events.
     * Selects top-rated players (rating > 70), creates callup entries,
     * and processes injuries (10% chance) and morale boosts (5% chance).
     *
     * @param season     the current season number
     * @param currentDay the current day in the season
     * @return list of created callup entries
     */
    public List<NationalTeamCallup> processInternationalBreak(int season, int currentDay) {
        // Select all players with rating > 70 (type 1 = player)
        List<Human> allPlayers = humanRepository.findAllByTypeId(1L);
        List<Human> eligiblePlayers = allPlayers.stream()
                .filter(p -> p.getRating() > MIN_RATING_FOR_CALLUP)
                .filter(p -> !p.isRetired())
                .filter(p -> p.getTeamId() != null)
                .collect(Collectors.toList());

        List<NationalTeamCallup> callups = new ArrayList<>();
        // Track callups and injuries per human team
        Map<Long, List<String>> humanTeamCalledUpMap = new HashMap<>();
        Map<Long, List<String>> humanTeamInjuredMap = new HashMap<>();

        for (Human player : eligiblePlayers) {
            // Check if player is already injured
            boolean alreadyInjured = injuryRepository
                    .findByPlayerIdAndDaysRemainingGreaterThan(player.getId(), 0)
                    .isPresent();
            if (alreadyInjured) {
                continue;
            }

            // Create callup entry
            NationalTeamCallup callup = new NationalTeamCallup();
            callup.setPlayerId(player.getId());
            callup.setPlayerName(player.getName());
            callup.setTeamId(player.getTeamId());
            callup.setSeasonNumber(season);
            callup.setStartDay(currentDay);
            callup.setEndDay(currentDay + INTERNATIONAL_BREAK_DURATION);
            callup.setReturned(false);
            callup.setInjuredDuringCallup(false);

            // Track human team callups
            if (userContext.isHumanTeam(player.getTeamId())) {
                humanTeamCalledUpMap.computeIfAbsent(player.getTeamId(), k -> new ArrayList<>()).add(player.getName());
            }

            // 10% chance of injury during international duty
            if (random.nextDouble() < INJURY_CHANCE) {
                int injuryDays = 7 + random.nextInt(15); // 7 to 21 days
                callup.setInjuredDuringCallup(true);

                Injury injury = new Injury();
                injury.setPlayerId(player.getId());
                injury.setTeamId(player.getTeamId());
                injury.setInjuryType("INTERNATIONAL_DUTY");
                injury.setSeverity(injuryDays <= 10 ? "MINOR" : "MODERATE");
                injury.setDaysRemaining(injuryDays);
                injury.setSeasonNumber(season);
                injuryRepository.save(injury);

                if (userContext.isHumanTeam(player.getTeamId())) {
                    humanTeamInjuredMap.computeIfAbsent(player.getTeamId(), k -> new ArrayList<>())
                            .add(player.getName() + " (" + injuryDays + " days)");
                }
            }

            // 5% chance of morale boost
            if (random.nextDouble() < MORALE_BOOST_CHANCE) {
                double newMorale = Math.min(player.getMorale() + 1.0, 100.0);
                player.setMorale(newMorale);
                humanRepository.save(player);
            }

            callup = nationalTeamCallupRepository.save(callup);
            callups.add(callup);
        }

        // Send inbox message to each human manager whose players were called up
        for (Map.Entry<Long, List<String>> entry : humanTeamCalledUpMap.entrySet()) {
            long htId = entry.getKey();
            List<String> calledUp = entry.getValue();
            List<String> injured = humanTeamInjuredMap.getOrDefault(htId, List.of());
            sendCallupNotification(htId, season, calledUp, injured);
        }

        return callups;
    }

    // ==================== RETURN PLAYERS ====================

    /**
     * Mark all callups for the season as returned.
     * Players become available for club selection again.
     *
     * @param season the season number
     */
    public void returnPlayers(int season) {
        List<NationalTeamCallup> unreturned = nationalTeamCallupRepository
                .findAllBySeasonNumberAndReturnedFalse(season);

        for (NationalTeamCallup callup : unreturned) {
            callup.setReturned(true);
            nationalTeamCallupRepository.save(callup);
        }
    }

    // ==================== QUERIES ====================

    /**
     * Return all callups for a given season.
     *
     * @param season the season number
     * @return list of all callups (returned and unreturned)
     */
    public List<NationalTeamCallup> getCallups(int season) {
        return nationalTeamCallupRepository.findAllBySeasonNumberAndReturnedFalse(season);
    }

    // ==================== PRIVATE HELPERS ====================

    /**
     * Send an inbox notification listing called-up players and any injuries.
     */
    private void sendCallupNotification(long teamId, int season, List<String> calledUp, List<String> injured) {
        StringBuilder content = new StringBuilder("The following players have been called up for international duty:\n\n");

        for (String name : calledUp) {
            content.append("- ").append(name).append("\n");
        }

        if (!injured.isEmpty()) {
            content.append("\nUnfortunately, the following players were injured during international duty:\n\n");
            for (String injuryInfo : injured) {
                content.append("- ").append(injuryInfo).append("\n");
            }
        }

        content.append("\nPlayers will return after the international break.");

        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(teamId);
        inbox.setSeasonNumber(season);
        inbox.setRoundNumber(0);
        inbox.setTitle("International Break: Player Callups");
        inbox.setContent(content.toString());
        inbox.setCategory("national_team");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);
    }
}
