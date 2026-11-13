package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PlayerTrainingCounter;
import com.footballmanagergamesimulator.repository.PlayerTrainingCounterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Training counters of human-managed teams: +1 on an attribute every session that trains
 * it, read on the monthly update day, then reset to 0. The month each attribute was last
 * trained is kept across resets, for the decline of long-untrained attributes.
 */
@Service
public class TrainingCounterService {

    @Autowired
    PlayerTrainingCounterRepository repository;

    /**
     * Records one session: each player's trained attributes count +1 and are marked trained
     * in {@code monthKey}. The first session of a player at this team also starts the clock
     * for his other {@code relevant} attributes (count 0, trained "now"), so that "untrained
     * for months" is measured from when he started training here.
     *
     * @param trained  player id → attributes this session trained
     * @param relevant player id → every attribute the player can have (outfield or goalkeeper)
     */
    @Transactional
    public void recordSession(long teamId, Map<Long, Set<String>> trained,
                              Map<Long, Set<String>> relevant, int monthKey) {
        Map<Long, Map<String, PlayerTrainingCounter>> existing = load(teamId, trained.keySet());
        List<PlayerTrainingCounter> changed = new ArrayList<>();
        for (Map.Entry<Long, Set<String>> entry : trained.entrySet()) {
            long playerId = entry.getKey();
            Map<String, PlayerTrainingCounter> rows = existing.getOrDefault(playerId, new HashMap<>());
            if (rows.isEmpty()) {
                for (String attribute : relevant.getOrDefault(playerId, Set.of())) {
                    rows.put(attribute, newRow(playerId, teamId, attribute, monthKey));
                }
            }
            for (String attribute : entry.getValue()) {
                PlayerTrainingCounter row = rows.computeIfAbsent(attribute,
                        name -> newRow(playerId, teamId, name, monthKey));
                row.setSessions(row.getSessions() + 1);
                row.setLastTrainedMonthKey(monthKey);
            }
            changed.addAll(rows.values());
        }
        repository.saveAll(changed);
    }

    /** Attribute → sessions since the last update, for the player at this team (empty if none). */
    public Map<String, Integer> counters(long teamId, long playerId) {
        Map<String, Integer> counters = new HashMap<>();
        load(teamId, List.of(playerId)).getOrDefault(playerId, Map.of())
                .forEach((attribute, row) -> counters.put(attribute, row.getSessions()));
        return counters;
    }

    /**
     * Attribute → whole months since it was last trained (0 = this month), for every
     * attribute the player has a row for at this team. Empty if he has not trained here yet.
     */
    public Map<String, Integer> monthsUntrained(long teamId, long playerId, int currentMonthKey) {
        Map<String, Integer> months = new HashMap<>();
        load(teamId, List.of(playerId)).getOrDefault(playerId, Map.of())
                .forEach((attribute, row) ->
                        months.put(attribute, Math.max(0, currentMonthKey - row.getLastTrainedMonthKey())));
        return months;
    }

    /** After the monthly update: every counter of the team back to 0. */
    @Transactional
    public void resetCounts(long teamId) {
        repository.resetCounts(teamId);
    }

    private Map<Long, Map<String, PlayerTrainingCounter>> load(long teamId, Collection<Long> playerIds) {
        Map<Long, Map<String, PlayerTrainingCounter>> byPlayer = new HashMap<>();
        if (playerIds.isEmpty()) return byPlayer;
        for (PlayerTrainingCounter row : repository.findAllByTeamIdAndPlayerIdIn(teamId, playerIds)) {
            byPlayer.computeIfAbsent(row.getPlayerId(), id -> new HashMap<>()).put(row.getAttribute(), row);
        }
        return byPlayer;
    }

    private static PlayerTrainingCounter newRow(long playerId, long teamId, String attribute, int monthKey) {
        PlayerTrainingCounter row = new PlayerTrainingCounter();
        row.setPlayerId(playerId);
        row.setTeamId(teamId);
        row.setAttribute(attribute);
        row.setSessions(0);
        row.setLastTrainedMonthKey(monthKey);
        return row;
    }
}
