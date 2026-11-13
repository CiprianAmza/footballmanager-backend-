package com.footballmanagergamesimulator.service.tournament;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Draw primitives for European competitions: pot-seeded group draws and seeded-vs-unseeded
 * pairings, both with best-effort same-nation avoidance. Match results are never produced
 * here; every real match is scored by Compartment V1.
 */
@Service
public class TournamentEngine {

    /**
     * Pot-seed a pre-ranked field into {@code groupCount} groups of {@code groupSize}.
     * {@code seededTeams} must be ordered strongest-first (the caller seeds, by club
     * coefficient in production). Standard pot draw: split into {@code groupSize} pots of
     * {@code groupCount} teams, shuffle each pot, place one team per pot in each group.
     *
     * <p>If {@code nationOf} is non-null, same-nation clashes within a group are avoided
     * best-effort by swapping with a later team in the same pot. Pass {@code null} for no
     * avoidance.
     */
    public List<List<Integer>> potSeededGroups(List<Integer> seededTeams, int groupCount, int groupSize,
                                               Random drawRng, java.util.function.IntToLongFunction nationOf) {
        List<List<Integer>> pots = new ArrayList<>(groupSize);
        for (int p = 0; p < groupSize; p++) {
            List<Integer> pot = new ArrayList<>(seededTeams.subList(p * groupCount, (p + 1) * groupCount));
            Collections.shuffle(pot, drawRng);
            pots.add(pot);
        }
        List<List<Integer>> groups = new ArrayList<>(groupCount);
        for (int g = 0; g < groupCount; g++) groups.add(new ArrayList<>(groupSize));
        for (List<Integer> pot : pots) {
            for (int g = 0; g < groupCount; g++) {
                int candidate = pot.get(g);
                if (nationOf != null) {
                    final int targetGroup = g;
                    long candNation = nationOf.applyAsLong(candidate);
                    boolean conflict = groups.get(g).stream().anyMatch(x -> nationOf.applyAsLong(x) == candNation);
                    if (conflict) {
                        for (int s = g + 1; s < groupCount; s++) {
                            int swapCand = pot.get(s);
                            long swapNation = nationOf.applyAsLong(swapCand);
                            boolean swapConflictTarget = groups.get(targetGroup).stream()
                                    .anyMatch(x -> nationOf.applyAsLong(x) == swapNation);
                            boolean origConflictSwapGroup = groups.get(s).stream()
                                    .anyMatch(x -> nationOf.applyAsLong(x) == candNation);
                            if (!swapConflictTarget && !origConflictSwapGroup) {
                                pot.set(g, swapCand);
                                pot.set(s, candidate);
                                candidate = swapCand;
                                break;
                            }
                        }
                    }
                }
                groups.get(g).add(candidate);
            }
        }
        return groups;
    }

    /**
     * Seeded knockout draw: shuffle the seeded (Pot 1) and unseeded (Pot 2) lists,
     * then pair {@code seeded[i]} (home) vs {@code unseeded[i]}, avoiding same-nation
     * ties best-effort by swapping a later unseeded team in. Returns the pairings as
     * {@code long[]{homeTeamId, awayTeamId}}. Caller persists them.
     *
     * <p>{@code nationOf} maps a team id to its nation id (-1 = unknown, never treated as
     * a clash). Pass {@code null} to skip nation avoidance.
     */
    public List<long[]> pairSeededVsUnseeded(List<Long> seeded, List<Long> unseeded,
                                             Random drawRng, java.util.function.LongUnaryOperator nationOf) {
        Collections.shuffle(seeded, drawRng);
        Collections.shuffle(unseeded, drawRng);
        int pairs = Math.min(seeded.size(), unseeded.size());
        if (nationOf != null) {
            for (int i = 0; i < pairs; i++) {
                long sNation = nationOf.applyAsLong(seeded.get(i));
                long uNation = nationOf.applyAsLong(unseeded.get(i));
                if (sNation == uNation && sNation != -1) {
                    for (int j = i + 1; j < unseeded.size(); j++) {
                        long swapNation = nationOf.applyAsLong(unseeded.get(j));
                        long seededAtJ = (j < seeded.size()) ? nationOf.applyAsLong(seeded.get(j)) : -1;
                        if (swapNation != sNation && uNation != seededAtJ) {
                            Collections.swap(unseeded, i, j);
                            break;
                        }
                    }
                }
            }
        }
        List<long[]> result = new ArrayList<>(pairs);
        for (int i = 0; i < pairs; i++) result.add(new long[]{seeded.get(i), unseeded.get(i)});
        return result;
    }
}
