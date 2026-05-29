package com.footballmanagergamesimulator.config;

import java.util.ArrayList;
import java.util.List;

/**
 * The ordered list of rounds for one European competition edition, DERIVED from
 * its configurable shape ({@code totalTeams}, {@code groupCount}, {@code groupSize},
 * {@code qualifyPerGroup}). This is the single source of truth that lets the
 * matchday dispatcher, the season calendar, and the coefficient/prize logic stop
 * hardcoding round numbers.
 *
 * <p>Structure (rounds are 0-based):
 * <pre>
 *   [0, P)            preliminary knockout rounds (trim totalTeams → slots)
 *   [P, P+G)          group stage (first round draws groups, last decides qualifiers)
 *   [P+G, P+G+K)      knockout (Round of qualifiers … QF, SF, Final)
 * </pre>
 * where {@code slots = groupCount*groupSize}, {@code qualifiers = groupCount*qualifyPerGroup},
 * {@code G = (groupSize-1)*2} (double round-robin), {@code K = log2(qualifiers)}, and
 * {@code P} is the number of trim rounds produced by repeatedly eliminating
 * {@code min(f-slots, f/2)} teams — the same rule as
 * {@code TournamentEngine.trimToSize}.
 */
public final class EuropeanFormatPlan {

    private final int totalTeams;
    private final int groupCount;
    private final int groupSize;
    private final int qualifyPerGroup;
    private final int slots;
    private final int qualifiers;
    private final int preliminaryRounds;
    private final int groupRounds;
    private final int knockoutRounds;
    private final List<EuropeanStage> stages;

    private EuropeanFormatPlan(int totalTeams, int groupCount, int groupSize, int qualifyPerGroup,
                              int slots, int qualifiers, int preliminaryRounds, int groupRounds,
                              int knockoutRounds, List<EuropeanStage> stages) {
        this.totalTeams = totalTeams;
        this.groupCount = groupCount;
        this.groupSize = groupSize;
        this.qualifyPerGroup = qualifyPerGroup;
        this.slots = slots;
        this.qualifiers = qualifiers;
        this.preliminaryRounds = preliminaryRounds;
        this.groupRounds = groupRounds;
        this.knockoutRounds = knockoutRounds;
        this.stages = stages;
    }

    /**
     * Derive a plan from a competition's configurable shape.
     *
     * @throws IllegalArgumentException if the shape can't form a clean tournament
     *         ({@code groupCount} not even, {@code qualifiers} not a power of two,
     *         {@code totalTeams < slots}, or any non-positive input).
     */
    public static EuropeanFormatPlan derive(int totalTeams, int groupCount, int groupSize, int qualifyPerGroup) {
        if (groupCount <= 0 || groupSize <= 0 || qualifyPerGroup <= 0) {
            throw new IllegalArgumentException("groupCount/groupSize/qualifyPerGroup must be positive: "
                    + groupCount + "/" + groupSize + "/" + qualifyPerGroup);
        }
        if (groupCount % 2 != 0) {
            throw new IllegalArgumentException("groupCount must be a multiple of 2, got " + groupCount);
        }
        int slots = groupCount * groupSize;
        int qualifiers = groupCount * qualifyPerGroup;
        if (qualifyPerGroup > groupSize) {
            throw new IllegalArgumentException("qualifyPerGroup (" + qualifyPerGroup
                    + ") cannot exceed groupSize (" + groupSize + ")");
        }
        if (!isPowerOfTwo(qualifiers)) {
            throw new IllegalArgumentException("groupCount*qualifyPerGroup must be a power of two for a "
                    + "clean knockout bracket, got " + qualifiers);
        }
        if (totalTeams < slots) {
            throw new IllegalArgumentException("totalTeams (" + totalTeams
                    + ") cannot be fewer than the group-stage slots (" + slots + ")");
        }

        // Preliminary trim: replay TournamentEngine.trimToSize's elimination rule
        // to know how many rounds there are and how big the field is each round.
        List<Integer> prelimFieldSizes = new ArrayList<>();
        int f = totalTeams;
        while (f > slots) {
            prelimFieldSizes.add(f);
            f -= Math.min(f - slots, f / 2);
        }
        int p = prelimFieldSizes.size();
        int g = (groupSize - 1) * 2;
        int k = Integer.numberOfTrailingZeros(qualifiers); // log2(qualifiers)

        List<EuropeanStage> stages = new ArrayList<>(p + g + k);
        int round = 0;

        for (int i = 0; i < p; i++) {
            stages.add(new EuropeanStage(round, round + 1, EuropeanPhase.PRELIMINARY,
                    prelimFieldSizes.get(i), false, false, true, false, 0));
            round++;
        }
        for (int gi = 0; gi < g; gi++) {
            stages.add(new EuropeanStage(round, round + 1, EuropeanPhase.GROUP,
                    slots, gi == 0, gi == g - 1, false, false, 0));
            round++;
        }
        int koTeams = qualifiers;
        for (int ki = 0; ki < k; ki++) {
            int roundsFromFinal = k - ki;          // first KO round = k, …, final = 1
            boolean twoLeg = roundsFromFinal >= 2; // every KO round but the final is two-legged
            boolean seededDraw = ki == 0;          // only the entry knockout round is drawn
            stages.add(new EuropeanStage(round, round + 1, EuropeanPhase.KNOCKOUT,
                    koTeams, false, false, seededDraw, twoLeg, roundsFromFinal));
            round++;
            koTeams /= 2;
        }

        return new EuropeanFormatPlan(totalTeams, groupCount, groupSize, qualifyPerGroup,
                slots, qualifiers, p, g, k, List.copyOf(stages));
    }

    private static boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

    public EuropeanStage stageForRound(int round) {
        if (round < 0 || round >= stages.size()) {
            throw new IllegalArgumentException("round " + round + " outside plan [0, " + stages.size() + ")");
        }
        return stages.get(round);
    }

    public List<EuropeanStage> stages() { return stages; }
    public int totalTeams() { return totalTeams; }
    public int groupCount() { return groupCount; }
    public int groupSize() { return groupSize; }
    public int qualifyPerGroup() { return qualifyPerGroup; }
    public int slots() { return slots; }
    public int qualifiers() { return qualifiers; }
    public int preliminaryRounds() { return preliminaryRounds; }
    public int groupRounds() { return groupRounds; }
    public int knockoutRounds() { return knockoutRounds; }
    public int totalRounds() { return stages.size(); }

    /** Round where the group stage begins (also the group-draw round). */
    public int groupStartRound() { return preliminaryRounds; }
    /** Round where the group stage ends (also the qualify round). */
    public int groupEndRound() { return preliminaryRounds + groupRounds - 1; }
    /** Round where the main knockout begins. */
    public int knockoutStartRound() { return preliminaryRounds + groupRounds; }
    /** Final round of the competition. */
    public int finalRound() { return stages.size() - 1; }
}
