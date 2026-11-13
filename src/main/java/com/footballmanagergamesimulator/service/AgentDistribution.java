package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.AgentPersonality;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Pure rules for who represents whom (design §3.2, "Cine are agent").
 * <ul>
 *   <li>World start: per nation, agents with random portfolios of 3–50 players; players shuffled between them.</li>
 *   <li>Youngsters start without an agent and take one around 18–20, from the agents of their club mates,
 *       each with a chance proportional to how many mates he already represents.</li>
 * </ul>
 */
public final class AgentDistribution {

    public static final int MIN_PORTFOLIO = 3;
    public static final int MAX_PORTFOLIO = 50;
    public static final int MIN_PATIENCE = 3;
    public static final int MAX_PATIENCE = 10;

    private AgentDistribution() {
    }

    /**
     * Shuffles one nation's players and cuts them into portfolios of 3–50.
     * A tail shorter than 3 joins the previous portfolio (so it can reach 52); a nation with
     * fewer than 3 players still gets one agent.
     */
    public static <T> List<List<T>> portfolios(List<T> players, Random rng) {
        List<T> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled, rng);
        List<List<T>> result = new ArrayList<>();
        int index = 0;
        while (index < shuffled.size()) {
            int size = MIN_PORTFOLIO + rng.nextInt(MAX_PORTFOLIO - MIN_PORTFOLIO + 1);
            int end = Math.min(shuffled.size(), index + size);
            List<T> chunk = new ArrayList<>(shuffled.subList(index, end));
            if (chunk.size() < MIN_PORTFOLIO && !result.isEmpty()) {
                result.get(result.size() - 1).addAll(chunk);
            } else {
                result.add(chunk);
            }
            index = end;
        }
        return result;
    }

    public static AgentPersonality personality(Random rng) {
        AgentPersonality[] values = AgentPersonality.values();
        return values[rng.nextInt(values.length)];
    }

    public static int patience(Random rng) {
        return MIN_PATIENCE + rng.nextInt(MAX_PATIENCE - MIN_PATIENCE + 1);
    }

    /**
     * Chance that an agentless player takes an agent this season, by his (new) age.
     * Defaults chosen by the implementer, to tune in play: 18 → 1/3, 19 → 1/2, 20+ → certain.
     */
    public static double takeAgentChance(int age) {
        if (age < 18) return 0.0;
        if (age == 18) return 1.0 / 3.0;
        if (age == 19) return 0.5;
        return 1.0;
    }

    /**
     * Picks an agent among the club mates' agents, weighted by how many mates each represents.
     * Returns 0 when no mate has an agent.
     */
    public static long pickFromClubMates(Map<Long, Integer> matesByAgent, Random rng) {
        int total = matesByAgent.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) return 0L;
        int roll = rng.nextInt(total);
        for (Map.Entry<Long, Integer> entry : matesByAgent.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            roll -= entry.getValue();
            if (roll < 0) return entry.getKey();
        }
        throw new IllegalStateException("unreachable");
    }
}
