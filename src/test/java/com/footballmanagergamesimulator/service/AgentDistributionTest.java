package com.footballmanagergamesimulator.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDistributionTest {

    @Test
    @DisplayName("""
            GIVEN a nation with 700 players
            WHEN the world-start portfolios are cut
            THEN every player lands with exactly one agent and every portfolio holds 3–52 players
            """)
    void portfoliosCoverEveryPlayerOnceWithinBounds() {
        for (int seed = 0; seed < 50; seed++) {
            List<Integer> players = IntStream.range(0, 700).boxed().toList();
            List<List<Integer>> portfolios = AgentDistribution.portfolios(players, new Random(seed));

            Set<Integer> seen = new HashSet<>();
            portfolios.forEach(seen::addAll);
            assertEquals(700, portfolios.stream().mapToInt(List::size).sum());
            assertEquals(700, seen.size());
            assertTrue(portfolios.stream().allMatch(p -> p.size() >= 3 && p.size() <= 52), "seed " + seed);
        }
    }

    @Test
    @DisplayName("""
            GIVEN a nation with only 2 players
            WHEN the portfolios are cut
            THEN one agent still represents both
            """)
    void tinyNationGetsOneAgent() {
        assertEquals(List.of(2), AgentDistribution.portfolios(List.of(1, 2), new Random(1))
                .stream().map(List::size).toList());
    }

    @Test
    @DisplayName("""
            GIVEN agent A representing 3 club mates and agent B representing 1
            WHEN many youngsters of that club pick an agent
            THEN A is chosen about three times as often as B
            """)
    void youngsterPicksMatesAgentProportionally() {
        Random rng = new Random(7);
        int a = 0;
        for (int i = 0; i < 40_000; i++) {
            if (AgentDistribution.pickFromClubMates(Map.of(10L, 3, 20L, 1), rng) == 10L) a++;
        }
        assertEquals(0.75, a / 40_000.0, 0.01);
        assertEquals(0L, AgentDistribution.pickFromClubMates(Map.of(), rng));
    }

    @Test
    @DisplayName("""
            GIVEN players of different ages without an agent
            WHEN the yearly chance to take one is read
            THEN nobody under 18 takes one, 18 → 1/3, 19 → 1/2, 20 and older always
            """)
    void takeAgentChanceByAge() {
        assertEquals(0.0, AgentDistribution.takeAgentChance(17));
        assertEquals(1.0 / 3.0, AgentDistribution.takeAgentChance(18));
        assertEquals(0.5, AgentDistribution.takeAgentChance(19));
        assertEquals(1.0, AgentDistribution.takeAgentChance(20));
        assertEquals(1.0, AgentDistribution.takeAgentChance(27));
    }

    @Test
    @DisplayName("""
            GIVEN many generated agents
            WHEN patience is drawn
            THEN it always lies in 3–10 and reaches both ends
            """)
    void patienceInRange() {
        Random rng = new Random(3);
        Set<Integer> values = new HashSet<>();
        for (int i = 0; i < 2_000; i++) values.add(AgentDistribution.patience(rng));
        assertEquals(Set.of(3, 4, 5, 6, 7, 8, 9, 10), values);
    }
}
