package com.footballmanagergamesimulator.service.odds;

import com.footballmanagergamesimulator.service.CupBracketService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.ToDoubleBiFunction;

/**
 * The rest of a knockout cup played many times over (design DESIGN_MEDIA_SI_COMPETITII §4.2). The bracket is fixed
 * at season start, so the ties already drawn stay and every winner moves on along the real bracket
 * ({@link CupBracketService#nextSlot}). Single matches only (national cups); a draw goes to penalties, 50/50.
 * Pure: no Spring, no database.
 */
public final class CupOddsSimulation {

    /**
     * One bracket match. {@code index} is 1-based within its round; a team id is 0 while the slot waits for an
     * earlier winner. A played match carries its winner (needed for the final only: earlier winners are already in
     * the next round's slots).
     */
    public record Match(int round, int index, long team1, long team2, boolean played, long winner) {}

    /** Chances as fractions 0..1: winning the cup, and playing the final. */
    public record Odds(long teamId, double win, double reachFinal) {}

    private CupOddsSimulation() {}

    /**
     * @param team1Advances probability that the first team goes through against the second, at the first's ground
     */
    public static List<Odds> simulate(List<Match> matches, ToDoubleBiFunction<Long, Long> team1Advances,
                                      int runs, long seed) {
        if (matches.isEmpty() || runs <= 0) return List.of();
        TreeMap<Integer, Map<Integer, Match>> rounds = new TreeMap<>();
        for (Match match : matches) {
            rounds.computeIfAbsent(match.round(), r -> new HashMap<>()).put(match.index(), match);
        }
        int lastRound = rounds.lastKey();
        Map<Long, Integer> wins = new HashMap<>();
        Map<Long, Integer> finals = new HashMap<>();
        Random random = new Random(seed);

        for (int run = 0; run < runs; run++) {
            Map<Integer, long[][]> slots = new HashMap<>();
            for (Map.Entry<Integer, Map<Integer, Match>> round : rounds.entrySet()) {
                int size = round.getValue().keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
                long[][] roundSlots = new long[size + 1][2];
                for (Match match : round.getValue().values()) {
                    roundSlots[match.index()][0] = match.team1();
                    roundSlots[match.index()][1] = match.team2();
                }
                slots.put(round.getKey(), roundSlots);
            }
            long champion = 0;
            for (Map.Entry<Integer, Map<Integer, Match>> round : rounds.entrySet()) {
                int roundNumber = round.getKey();
                long[][] roundSlots = slots.get(roundNumber);
                int size = roundSlots.length - 1;
                long[][] nextSlots = slots.get(roundNumber + 1);
                for (int index = 1; index <= size; index++) {
                    Match match = round.getValue().get(index);
                    if (match == null) continue;
                    long team1 = roundSlots[index][0];
                    long team2 = roundSlots[index][1];
                    boolean isFinal = roundNumber == lastRound;
                    if (isFinal) {
                        if (team1 > 0) finals.merge(team1, 1, Integer::sum);
                        if (team2 > 0) finals.merge(team2, 1, Integer::sum);
                    }
                    long winner;
                    if (match.played()) {
                        if (isFinal) champion = match.winner();
                        continue; // its winner is already in the next round's slot
                    }
                    if (team1 > 0 && team2 > 0) {
                        winner = random.nextDouble() < team1Advances.applyAsDouble(team1, team2) ? team1 : team2;
                    } else {
                        winner = team1 > 0 ? team1 : team2;
                    }
                    if (isFinal) {
                        champion = winner;
                    } else if (winner > 0 && nextSlots != null) {
                        int[] next = CupBracketService.nextSlot(size, nextSlots.length - 1, index);
                        if (next != null && next[0] < nextSlots.length) nextSlots[next[0]][next[1] - 1] = winner;
                    }
                }
            }
            if (champion > 0) wins.merge(champion, 1, Integer::sum);
        }

        List<Odds> odds = new ArrayList<>();
        java.util.Set<Long> teams = new java.util.HashSet<>(finals.keySet());
        teams.addAll(wins.keySet());
        for (long team : teams) {
            odds.add(new Odds(team, (double) wins.getOrDefault(team, 0) / runs,
                    (double) finals.getOrDefault(team, 0) / runs));
        }
        odds.sort(Comparator.comparingDouble(Odds::win).reversed().thenComparingLong(Odds::teamId));
        return odds;
    }
}
