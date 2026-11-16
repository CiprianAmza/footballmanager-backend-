package com.footballmanagergamesimulator.algorithms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Draws a multi-meeting round-robin (the Diamond League format) from a seed.
 *
 * <p>The schedule is built from blocks of two legs. Each block draws a fresh
 * single round-robin (circle method over a shuffled field), plays it once with the
 * drawn orientation and once mirrored, and shuffles the round order of each leg.
 * With {@code encounters = 4} that is two blocks: every pair meets four times,
 * twice at each ground, every club plays exactly once per round, and the
 * {@code encounters * (n - 1)} rounds come out in a drawn order. Consecutive
 * rounds never repeat a pairing.
 *
 * <p>Pure and deterministic: the same set of teams, encounter count and seed always
 * produce the same schedule, regardless of the order the teams are given in.
 */
public final class DiamondLeagueScheduleGenerator {

    /** One match: {@code homeTeamId} hosts {@code awayTeamId}. */
    public record Fixture(long homeTeamId, long awayTeamId) {}

    private static final int MAX_REDRAWS = 200;

    private DiamondLeagueScheduleGenerator() {}

    /**
     * @param teamIds    distinct team ids; an even count of at least two
     * @param encounters meetings per pair; an even number of at least two
     * @param seed       draw seed
     * @return the rounds in play order; each round lists {@code n / 2} fixtures
     */
    public static List<List<Fixture>> generate(List<Long> teamIds, int encounters, long seed) {
        Set<Long> distinct = new TreeSet<>(teamIds);
        if (distinct.size() != teamIds.size()) {
            throw new IllegalArgumentException("Team ids must be distinct: " + teamIds);
        }
        if (distinct.size() < 2 || distinct.size() % 2 != 0) {
            throw new IllegalArgumentException("Need an even number of at least two teams, got " + distinct.size());
        }
        if (encounters < 2 || encounters % 2 != 0) {
            throw new IllegalArgumentException("Encounters must be an even number of at least two, got " + encounters);
        }

        Random random = new Random(seed);
        List<Long> sorted = new ArrayList<>(distinct);
        List<List<Fixture>> schedule = new ArrayList<>();
        for (int block = 0; block < encounters / 2; block++) {
            List<Long> field = new ArrayList<>(sorted);
            Collections.shuffle(field, random);
            List<List<Fixture>> firstLeg = singleRoundRobin(field);
            List<List<Fixture>> secondLeg = new ArrayList<>(firstLeg.size());
            for (List<Fixture> round : firstLeg) secondLeg.add(mirror(round));

            appendShuffled(schedule, firstLeg, random);
            appendShuffled(schedule, secondLeg, random);
        }
        return schedule;
    }

    /** Circle method: the first club stays put, the others rotate one place per round. */
    static List<List<Fixture>> singleRoundRobin(List<Long> field) {
        int n = field.size();
        List<Long> ring = new ArrayList<>(field);
        List<List<Fixture>> rounds = new ArrayList<>(n - 1);
        for (int r = 0; r < n - 1; r++) {
            List<Fixture> round = new ArrayList<>(n / 2);
            for (int i = 0; i < n / 2; i++) {
                long a = ring.get(i);
                long b = ring.get(n - 1 - i);
                // The fixed club alternates ground each round; the rotating clubs
                // change ground as they move between the two rows of the circle.
                boolean swap = i == 0 && r % 2 == 1;
                round.add(swap ? new Fixture(b, a) : new Fixture(a, b));
            }
            rounds.add(round);
            // rotate positions 1..n-1 by one
            Long last = ring.remove(n - 1);
            ring.add(1, last);
        }
        return rounds;
    }

    private static List<Fixture> mirror(List<Fixture> round) {
        List<Fixture> mirrored = new ArrayList<>(round.size());
        for (Fixture f : round) mirrored.add(new Fixture(f.awayTeamId(), f.homeTeamId()));
        return mirrored;
    }

    /**
     * Appends the leg's rounds in a drawn order whose first round shares no pairing
     * with the round already played last (rounds inside one leg never share one).
     */
    private static void appendShuffled(List<List<Fixture>> schedule, List<List<Fixture>> leg, Random random) {
        List<List<Fixture>> order = new ArrayList<>(leg);
        Collections.shuffle(order, random);
        if (!schedule.isEmpty()) {
            Set<String> previous = pairKeys(schedule.get(schedule.size() - 1));
            for (int attempt = 0; attempt < MAX_REDRAWS && sharesPair(previous, order.get(0)); attempt++) {
                Collections.shuffle(order, random);
            }
        }
        schedule.addAll(order);
    }

    private static boolean sharesPair(Set<String> previous, List<Fixture> round) {
        for (String key : pairKeys(round)) if (previous.contains(key)) return true;
        return false;
    }

    private static Set<String> pairKeys(List<Fixture> round) {
        Set<String> keys = new HashSet<>();
        for (Fixture f : round) {
            long low = Math.min(f.homeTeamId(), f.awayTeamId());
            long high = Math.max(f.homeTeamId(), f.awayTeamId());
            keys.add(low + "-" + high);
        }
        return keys;
    }
}
