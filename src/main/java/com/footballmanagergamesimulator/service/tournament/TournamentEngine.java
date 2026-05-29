package com.footballmanagergamesimulator.service.tournament;

import com.footballmanagergamesimulator.service.MatchSimulationService;
import com.footballmanagergamesimulator.service.knockout.KnockoutTieResolver;
import com.footballmanagergamesimulator.service.knockout.LegFormat;
import com.footballmanagergamesimulator.service.knockout.TieResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Reusable, format-agnostic tournament primitives shared by the outcome
 * simulations (and, in a later phase, by production draw/qualify logic).
 *
 * <p>Everything operates on <b>team indices</b> into a caller-supplied
 * {@code double[] powers} array, so the engine is decoupled from any entity
 * type. Match scores come from the production {@link MatchSimulationService}
 * (config-driven, seeded via {@code setRandomForTesting}); knockout ties go
 * through the production {@link KnockoutTieResolver} (extra time → penalties,
 * single- or two-leg). Structural randomness (draws, shuffles, tie RNG) uses a
 * caller-supplied {@link Random} so the caller controls determinism.
 *
 * <p>Methods return structured results (rounds, ties, group standings) so the
 * caller can both aggregate statistics and render a phase-by-phase log without
 * the engine knowing anything about reporting.
 */
@Service
public class TournamentEngine {

    @Autowired private MatchSimulationService matchSim;
    @Autowired private KnockoutTieResolver tieResolver;

    // ==================== result records ====================

    /** One resolved knockout tie. {@code aIdx}/{@code bIdx} are the drawn teams,
     *  {@code winnerIdx} the team that advanced, {@code tie} the full resolver result. */
    public record TieOutcome(int aIdx, int bIdx, int winnerIdx, TieResult tie) {
        public int loserIdx() { return winnerIdx == aIdx ? bIdx : aIdx; }
    }

    /** One preliminary round: the ties played plus the teams that got a bye. */
    public record PrelimRound(List<TieOutcome> ties, List<Integer> byes) {}

    /** Result of trimming a field down to the target size via preliminaries. */
    public record PrelimResult(List<Integer> survivors, List<PrelimRound> rounds) {}

    /** One group match (team indices are global; goals as played). */
    public record GroupMatch(int homeIdx, int awayIdx, int homeGoals, int awayGoals) {}

    /**
     * Final state of one group. {@code teams} are the group's global indices in
     * draw order (local index = position here). {@code points/goalsFor/goalsAgainst}
     * are indexed by local position. {@code standingOrderLocal} lists local indices
     * best-first. {@code matchdays} holds the matches played, one inner list per
     * matchday in schedule order.
     */
    public record GroupResult(int groupIndex, List<Integer> teams,
                              int[] points, int[] goalsFor, int[] goalsAgainst,
                              List<Integer> standingOrderLocal,
                              List<List<GroupMatch>> matchdays) {
        /** Global team index finishing at the given 0-based position. */
        public int teamAtPosition(int pos) { return teams.get(standingOrderLocal.get(pos)); }
        public int pointsAtPosition(int pos) { return points[standingOrderLocal.get(pos)]; }
        public int goalsForAtPosition(int pos) { return goalsFor[standingOrderLocal.get(pos)]; }
        public int goalsAgainstAtPosition(int pos) { return goalsAgainst[standingOrderLocal.get(pos)]; }
    }

    /** One knockout round: its bracket size (entrants) and the ties played. */
    public record KnockoutRound(int bracketSize, List<TieOutcome> ties) {}

    /** Knockout result: the champion (or -1) plus every round in order. */
    public record KnockoutResult(int championIdx, List<KnockoutRound> rounds) {}

    /** Per-team league tallies, indexed by global team index (array length = powers.length). */
    public record LeagueTally(int[] points, int[] goalsFor, int[] goalsAgainst,
                              int[] wins, int[] draws, int[] losses) {}

    /**
     * Single-elimination cup result. Per-team arrays indexed by global team index.
     * {@code stageReached} is the smallest bracket size a team was alive in
     * (champion = 1). {@code bracketSize} is the padded power-of-two field size.
     */
    public record CupResult(int championIdx, int[] matchesPlayed, int[] matchesWon,
                            int[] stageReached, int bracketSize) {}

    // ==================== primitives ====================

    /** Indices sorted by power descending (no names available → ties keep input order, stable sort). */
    private List<Integer> seededByPower(List<Integer> teams, double[] powers) {
        List<Integer> out = new ArrayList<>(teams);
        out.sort(Comparator.comparingDouble((Integer i) -> powers[i]).reversed());
        return out;
    }

    /**
     * Trim {@code field} down to {@code targetSize} via single/two-leg knockout
     * rounds: the strongest seeds get byes, the weakest {@code 2*eliminate} play.
     * Returns the survivors plus a per-round log. If the field already has
     * {@code targetSize} (or fewer) teams, returns it unchanged with no rounds.
     */
    public PrelimResult trimToSize(List<Integer> field, double[] powers, int targetSize,
                                   LegFormat leg, Random drawRng) {
        List<Integer> current = new ArrayList<>(field);
        List<PrelimRound> rounds = new ArrayList<>();
        while (current.size() > targetSize) {
            int f = current.size();
            int eliminate = Math.min(f - targetSize, f / 2);
            current.sort(Comparator.comparingDouble((Integer i) -> powers[i]).reversed());
            List<Integer> byes = new ArrayList<>(current.subList(0, f - 2 * eliminate));
            List<Integer> playing = new ArrayList<>(current.subList(f - 2 * eliminate, f));
            Collections.shuffle(playing, drawRng);

            List<TieOutcome> ties = new ArrayList<>();
            List<Integer> survivors = new ArrayList<>(byes);
            for (int i = 0; i + 1 < playing.size(); i += 2) {
                TieOutcome t = playTie(playing.get(i), playing.get(i + 1), powers, leg, drawRng);
                ties.add(t);
                survivors.add(t.winnerIdx());
            }
            rounds.add(new PrelimRound(ties, byes));
            current = survivors;
        }
        return new PrelimResult(current, rounds);
    }

    /**
     * Pot-seed {@code teams} into {@code groupCount} groups of {@code groupSize}:
     * sort by power, split into pots, shuffle each pot, one team per pot per group.
     */
    public List<List<Integer>> potSeededGroups(List<Integer> teams, double[] powers,
                                               int groupCount, int groupSize, Random drawRng) {
        List<Integer> seeded = seededByPower(teams, powers);
        List<List<Integer>> groups = new ArrayList<>(groupCount);
        for (int g = 0; g < groupCount; g++) groups.add(new ArrayList<>(groupSize));
        for (int pot = 0; pot < groupSize; pot++) {
            List<Integer> potTeams = new ArrayList<>(seeded.subList(pot * groupCount, (pot + 1) * groupCount));
            Collections.shuffle(potTeams, drawRng);
            for (int g = 0; g < groupCount; g++) groups.get(g).add(potTeams.get(g));
        }
        return groups;
    }

    /**
     * Play every group over the given double-round-robin {@code schedule}
     * (matchday-major, group-minor — matching the order scores are drawn).
     * Standings tiebreak: points → goal difference → goals for → power.
     */
    public List<GroupResult> playGroups(List<List<Integer>> groups, double[] powers, int[][][] schedule) {
        int g = groups.size();
        int groupSize = groups.isEmpty() ? 0 : groups.get(0).size();
        int[][] pts = new int[g][groupSize];
        int[][] gf = new int[g][groupSize];
        int[][] ga = new int[g][groupSize];
        List<List<List<GroupMatch>>> matchdaysByGroup = new ArrayList<>();
        for (int gi = 0; gi < g; gi++) matchdaysByGroup.add(new ArrayList<>());

        for (int[][] matchday : schedule) {
            for (int gi = 0; gi < g; gi++) {
                List<Integer> group = groups.get(gi);
                List<GroupMatch> mdMatches = new ArrayList<>(matchday.length);
                for (int[] pair : matchday) {
                    int homeLocal = pair[0], awayLocal = pair[1];
                    int homeIdx = group.get(homeLocal), awayIdx = group.get(awayLocal);
                    List<Integer> scores = matchSim.calculateScores(powers[homeIdx], powers[awayIdx]);
                    int sH = scores.get(0), sA = scores.get(1);
                    gf[gi][homeLocal] += sH; ga[gi][homeLocal] += sA;
                    gf[gi][awayLocal] += sA; ga[gi][awayLocal] += sH;
                    if (sH > sA) pts[gi][homeLocal] += 3;
                    else if (sH == sA) { pts[gi][homeLocal]++; pts[gi][awayLocal]++; }
                    else pts[gi][awayLocal] += 3;
                    mdMatches.add(new GroupMatch(homeIdx, awayIdx, sH, sA));
                }
                matchdaysByGroup.get(gi).add(mdMatches);
            }
        }

        List<GroupResult> results = new ArrayList<>(g);
        for (int gi = 0; gi < g; gi++) {
            List<Integer> group = groups.get(gi);
            Integer[] localOrder = new Integer[groupSize];
            for (int i = 0; i < groupSize; i++) localOrder[i] = i;
            final int gg = gi;
            java.util.Arrays.sort(localOrder, (a, b) -> {
                if (pts[gg][a] != pts[gg][b]) return pts[gg][b] - pts[gg][a];
                int gdA = gf[gg][a] - ga[gg][a], gdB = gf[gg][b] - ga[gg][b];
                if (gdA != gdB) return gdB - gdA;
                if (gf[gg][a] != gf[gg][b]) return gf[gg][b] - gf[gg][a];
                return Double.compare(powers[group.get(b)], powers[group.get(a)]);
            });
            results.add(new GroupResult(gi, group, pts[gi], gf[gi], ga[gi],
                    List.of(localOrder), matchdaysByGroup.get(gi)));
        }
        return results;
    }

    /**
     * Single-elimination knockout among {@code entrants} (shuffled with
     * {@code drawRng}). Plays rounds until one team remains. Returns the champion
     * and every round.
     */
    public KnockoutResult runKnockout(List<Integer> entrants, double[] powers, LegFormat leg, Random drawRng) {
        List<Integer> alive = new ArrayList<>(entrants);
        Collections.shuffle(alive, drawRng);
        List<KnockoutRound> rounds = new ArrayList<>();
        int champion = alive.size() == 1 ? alive.get(0) : -1;
        while (alive.size() > 1) {
            int bracketSize = alive.size();
            List<TieOutcome> ties = new ArrayList<>(bracketSize / 2);
            List<Integer> next = new ArrayList<>(bracketSize / 2);
            for (int i = 0; i + 1 < bracketSize; i += 2) {
                TieOutcome t = playTie(alive.get(i), alive.get(i + 1), powers, leg, drawRng);
                ties.add(t);
                next.add(t.winnerIdx());
            }
            rounds.add(new KnockoutRound(bracketSize, ties));
            if (bracketSize == 2) champion = next.get(0);
            alive = next;
        }
        return new KnockoutResult(champion, rounds);
    }

    /**
     * Shuffle {@code entrants} with {@code drawRng} and play exactly one round of
     * ties (each consecutive pair). Used for one-off rounds such as the Stars Cup
     * playoff (8 teams → 4 winners). Returns the round's ties.
     */
    public KnockoutRound drawAndPlayRound(List<Integer> entrants, double[] powers, LegFormat leg, Random drawRng) {
        List<Integer> shuffled = new ArrayList<>(entrants);
        Collections.shuffle(shuffled, drawRng);
        List<TieOutcome> ties = new ArrayList<>(shuffled.size() / 2);
        for (int i = 0; i + 1 < shuffled.size(); i += 2) {
            ties.add(playTie(shuffled.get(i), shuffled.get(i + 1), powers, leg, drawRng));
        }
        return new KnockoutRound(shuffled.size(), ties);
    }

    /** Resolve a single knockout tie (single- or two-leg) via the production resolver. */
    public TieOutcome playTie(int aIdx, int bIdx, double[] powers, LegFormat leg, Random rng) {
        TieResult tie = tieResolver.resolve(powers[aIdx], powers[bIdx], leg, rng);
        int winner = tie.teamAWon() ? aIdx : bIdx;
        return new TieOutcome(aIdx, bIdx, winner, tie);
    }

    /**
     * Double round-robin: every ordered pair of {@code teams} plays once (so each
     * unordered pair plays home + away). Scores come from {@link MatchSimulationService}.
     * Returns raw per-team tallies; the caller applies its own standings tiebreak
     * (the engine has no team names). Array length = {@code powers.length}.
     */
    public LeagueTally playDoubleRoundRobin(List<Integer> teams, double[] powers) {
        int len = powers.length;
        int[] points = new int[len], gf = new int[len], ga = new int[len];
        int[] wins = new int[len], draws = new int[len], losses = new int[len];
        for (int home : teams) {
            for (int away : teams) {
                if (home == away) continue;
                List<Integer> scores = matchSim.calculateScores(powers[home], powers[away]);
                int sH = scores.get(0), sA = scores.get(1);
                gf[home] += sH; ga[home] += sA;
                gf[away] += sA; ga[away] += sH;
                if (sH > sA) { points[home] += 3; wins[home]++; losses[away]++; }
                else if (sH == sA) { points[home]++; points[away]++; draws[home]++; draws[away]++; }
                else { points[away] += 3; wins[away]++; losses[home]++; }
            }
        }
        return new LeagueTally(points, gf, ga, wins, draws, losses);
    }

    /**
     * Single-elimination cup. {@code seededTeams} must be ordered strongest-first
     * (the caller seeds — it owns any name tiebreak). The top {@code nextPow2 - n}
     * seeds get a round-one bye; the rest are shuffled with {@code drawRng}. Level
     * ties are resolved by {@link #playTie}. Returns champion + per-team stats.
     */
    public CupResult runCupWithByes(List<Integer> seededTeams, double[] powers, LegFormat leg, Random drawRng) {
        int n = seededTeams.size();
        int nextPow2 = Integer.highestOneBit(n);
        if (nextPow2 < n) nextPow2 <<= 1;
        int numByes = nextPow2 - n;

        List<Integer> byeTeams = new ArrayList<>(seededTeams.subList(0, numByes));
        List<Integer> playing = new ArrayList<>(seededTeams.subList(numByes, n));
        Collections.shuffle(playing, drawRng);

        // Round-one bracket: each bye paired with sentinel -1, then the drawn pairs.
        List<Integer> alive = new ArrayList<>(nextPow2);
        for (int b : byeTeams) { alive.add(b); alive.add(-1); }
        alive.addAll(playing);

        int[] matchesPlayed = new int[powers.length];
        int[] matchesWon = new int[powers.length];
        int[] stageReached = new int[powers.length];
        java.util.Arrays.fill(stageReached, nextPow2);

        int champion = -1;
        while (alive.size() > 1) {
            int roundSize = alive.size();
            for (int slot : alive) {
                if (slot >= 0 && roundSize < stageReached[slot]) stageReached[slot] = roundSize;
            }
            List<Integer> next = new ArrayList<>(roundSize / 2);
            for (int i = 0; i < roundSize; i += 2) {
                int a = alive.get(i), b = alive.get(i + 1);
                int winner;
                if (a == -1) winner = b;
                else if (b == -1) winner = a;
                else {
                    winner = playTie(a, b, powers, leg, drawRng).winnerIdx();
                    matchesPlayed[a]++; matchesPlayed[b]++; matchesWon[winner]++;
                }
                next.add(winner);
            }
            if (roundSize == 2) champion = next.get(0);
            alive = next;
        }
        return new CupResult(champion, matchesPlayed, matchesWon, stageReached, nextPow2);
    }
}
