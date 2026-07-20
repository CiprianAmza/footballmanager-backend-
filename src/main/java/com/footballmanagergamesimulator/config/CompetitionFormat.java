package com.footballmanagergamesimulator.config;

import java.util.Map;
import java.util.Set;

/**
 * Structural description of one competition type's tournament format: how
 * matchdays map to rounds, group-stage shape, which rounds are groups vs
 * knockout, how many teams qualify, where eliminated teams drop, and which
 * knockout rounds are two-leg.
 *
 * <p>This is the <b>single source of truth for tournament structure</b> —
 * the counterpart to {@link MatchEngineConfig} (which owns the scoring math).
 * Production (draw, fixtures, qualification, matchday dispatch) reads these
 * values instead of hardcoding magic round numbers. Instances are built in
 * {@link CompetitionFormatConfig} with the values that were previously
 * scattered across the engine services, so behaviour is unchanged.
 *
 * <p>Round numbering (defaults):
 * <ul>
 *   <li>LoC (type 4): matchday-1 = round. 0-1 preliminary, 2-7 groups,
 *       8 QF, 9 SF, 10 Final.</li>
 *   <li>Stars Cup (type 5): matchday = round. 1-6 groups, 7 playoff,
 *       8 QF, 9 SF, 10 Final.</li>
 *   <li>League / Cup (types 1, 2, 3): matchday = round.</li>
 * </ul>
 */
public final class CompetitionFormat {

    public enum Kind { LEAGUE, KNOCKOUT, GROUPS_THEN_KNOCKOUT }

    private final int typeId;
    private final Kind kind;
    /** Added to matchday to get the round number (LoC = -1, all others = 0). */
    private final int matchdayToRoundDelta;

    // ---- league scheduling (LEAGUE only) ----
    /** How many times each pair meets, by exact team count (e.g. {12=4, 14=3}). */
    private final Map<Integer, Integer> encountersByTeamCount;
    /** Encounters when the team count isn't listed above (default round-robin = 2). */
    private final int defaultEncounters;

    // ---- group stage (GROUPS_THEN_KNOCKOUT only) ----
    /** Total entrants the competition is sized for (drives the preliminary-round count). */
    private final int totalTeams;
    private final int groupCount;
    private final int groupSize;
    private final int qualifyPerGroupToKnockout; // LoC: 2, SC: 1
    private final int playoffQualifyPerGroup;    // group positions routed to a playoff (SC: 1, LoC: 0)
    private final int qualifyTargetRound;        // round group qualifiers enter (8 = QF)
    private final int groupStartRound;           // also the group-draw round
    private final int groupEndRound;             // also the qualify round
    private final int groupFixtureRoundOffset;   // LoC: 1, SC: 0

    // ---- knockout ----
    private final int knockoutStartRound;        // LoC: 8, SC: 7
    private final int finalRound;                // 10
    private final int playoffRound;              // SC: 7 (0 = none)

    // ---- drops to a secondary competition ----
    private final int thirdPlaceDropTypeId;      // LoC: 5 (Stars Cup), else 0
    private final int thirdPlaceDropRound;       // target round in that competition (SC playoff = 7)
    private final int losersDropTypeId;          // LoC prelim/qualifying losers → SC (5), else 0
    private final int losersDropRound;           // target round (SC group = 1)

    // ---- round sets ----
    private final Set<Integer> preliminaryRounds;        // LoC: {0, 1}
    private final Set<Integer> seededKnockoutDrawRounds; // LoC: {0, 1}; SC: {7}
    private final Set<Integer> twoLegRounds;             // LoC: {8, 9}

    /** Derived round plan (preliminary/group/knockout structure); null unless a sized group stage. */
    private final EuropeanFormatPlan europeanPlan;

    private CompetitionFormat(Builder b) {
        this.typeId = b.typeId;
        this.kind = b.kind;
        this.matchdayToRoundDelta = b.matchdayToRoundDelta;
        this.encountersByTeamCount = Map.copyOf(b.encountersByTeamCount);
        this.defaultEncounters = b.defaultEncounters;
        this.totalTeams = b.totalTeams;
        this.groupCount = b.groupCount;
        this.groupSize = b.groupSize;
        this.qualifyPerGroupToKnockout = b.qualifyPerGroupToKnockout;
        this.playoffQualifyPerGroup = b.playoffQualifyPerGroup;
        this.qualifyTargetRound = b.qualifyTargetRound;
        this.groupStartRound = b.groupStartRound;
        this.groupEndRound = b.groupEndRound;
        this.groupFixtureRoundOffset = b.groupFixtureRoundOffset;
        this.knockoutStartRound = b.knockoutStartRound;
        this.finalRound = b.finalRound;
        this.playoffRound = b.playoffRound;
        this.thirdPlaceDropTypeId = b.thirdPlaceDropTypeId;
        this.thirdPlaceDropRound = b.thirdPlaceDropRound;
        this.losersDropTypeId = b.losersDropTypeId;
        this.losersDropRound = b.losersDropRound;
        this.preliminaryRounds = Set.copyOf(b.preliminaryRounds);
        this.seededKnockoutDrawRounds = Set.copyOf(b.seededKnockoutDrawRounds);
        this.twoLegRounds = Set.copyOf(b.twoLegRounds);
        this.europeanPlan = b.tieredEuropeanEntries != null
                ? EuropeanFormatPlan.deriveTiered(
                        b.tieredEuropeanEntries[0], b.tieredEuropeanEntries[1], b.tieredEuropeanEntries[2],
                        groupCount, groupSize, qualifyPerGroupToKnockout)
                : (kind == Kind.GROUPS_THEN_KNOCKOUT && totalTeams > 0)
                    ? EuropeanFormatPlan.derive(totalTeams, groupCount, groupSize, qualifyPerGroupToKnockout)
                    : null;
    }

    public int totalTeams() { return totalTeams; }

    /** Derived round plan, or null for formats that aren't a sized group stage. */
    public EuropeanFormatPlan europeanPlan() { return europeanPlan; }

    public int typeId() { return typeId; }
    public Kind kind() { return kind; }
    public int matchdayToRoundDelta() { return matchdayToRoundDelta; }
    public int groupCount() { return groupCount; }
    public int groupSize() { return groupSize; }
    public int qualifyPerGroupToKnockout() { return qualifyPerGroupToKnockout; }
    /** Group finishing positions (after the direct-to-knockout ones) routed to a playoff. */
    public int playoffQualifyPerGroup() { return playoffQualifyPerGroup; }

    // When a derived plan exists (sized group stage), round boundaries come from
    // it so changing totalTeams/groups/qualifyPerGroup adapts the whole format in
    // one place. Otherwise the explicit builder values are used (e.g. Stars Cup,
    // whose bespoke playoff isn't modelled by the plan).

    /** Round group qualifiers enter the knockout (= first knockout round). */
    public int qualifyTargetRound() {
        return europeanPlan != null ? europeanPlan.knockoutStartRound() : qualifyTargetRound;
    }
    public int groupStartRound() {
        return europeanPlan != null ? europeanPlan.groupStartRound() : groupStartRound;
    }
    public int groupEndRound() {
        return europeanPlan != null ? europeanPlan.groupEndRound() : groupEndRound;
    }
    public int groupFixtureRoundOffset() { return groupFixtureRoundOffset; }
    public int knockoutStartRound() {
        return europeanPlan != null ? europeanPlan.knockoutStartRound() : knockoutStartRound;
    }
    public int finalRound() {
        return europeanPlan != null ? europeanPlan.finalRound() : finalRound;
    }
    public int playoffRound() { return playoffRound; }
    public int thirdPlaceDropTypeId() { return thirdPlaceDropTypeId; }
    public int thirdPlaceDropRound() { return thirdPlaceDropRound; }
    public int losersDropTypeId() { return losersDropTypeId; }
    public int losersDropRound() { return losersDropRound; }

    /** Round number for a 1-based matchday. */
    public int roundForMatchday(int matchday) { return matchday + matchdayToRoundDelta; }

    /**
     * How many times each pair of teams meets in a season for a league of the given
     * size — e.g. 12 teams → 4, 14 → 3, anything else → {@link #defaultEncounters}.
     * Odd values are supported (the extra meeting alternates home advantage).
     */
    public int encountersFor(int teamCount) {
        return encountersByTeamCount.getOrDefault(teamCount, defaultEncounters);
    }

    public boolean isGroupRound(long round) {
        return kind == Kind.GROUPS_THEN_KNOCKOUT && round >= groupStartRound() && round <= groupEndRound();
    }
    public boolean isGroupDrawRound(long round) { return round == groupStartRound(); }
    public boolean isQualifyRound(long round) { return round == groupEndRound(); }

    public boolean isPreliminaryRound(long round) {
        if (europeanPlan != null) {
            return round >= 0 && round < europeanPlan.preliminaryRounds();
        }
        return preliminaryRounds.contains((int) round);
    }

    /**
     * True when this round's pairing is coefficient-seeded (preliminary byes).
     * Knockout-phase draws are random shuffles, so only the preliminary rounds
     * count as seeded for a sized plan.
     */
    public boolean isSeededKnockoutDrawRound(long round) {
        if (europeanPlan != null) {
            return round >= 0 && round < europeanPlan.preliminaryRounds();
        }
        return seededKnockoutDrawRounds.contains((int) round);
    }

    public boolean isTwoLeg(long round) {
        if (europeanPlan != null) {
            return round >= 0 && round < europeanPlan.totalRounds()
                    && europeanPlan.stageForRound((int) round).twoLeg();
        }
        return twoLegRounds.contains((int) round);
    }

    /** Number of matchdays the group stage spans (e.g. 6 for a group of 4, double round-robin). */
    public int groupMatchdayCount() { return groupEndRound() - groupStartRound() + 1; }

    public static Builder builder(int typeId, Kind kind) { return new Builder(typeId, kind); }

    public static final class Builder {
        private final int typeId;
        private final Kind kind;
        private int matchdayToRoundDelta = 0;
        private Map<Integer, Integer> encountersByTeamCount = Map.of();
        private int defaultEncounters = 2;
        private int totalTeams = 0;
        private int groupCount = 0;
        private int groupSize = 0;
        private int qualifyPerGroupToKnockout = 0;
        private int playoffQualifyPerGroup = 0;
        private int qualifyTargetRound = 0;
        private int groupStartRound = 0;
        private int groupEndRound = 0;
        private int groupFixtureRoundOffset = 0;
        private int knockoutStartRound = 0;
        private int finalRound = 0;
        private int playoffRound = 0;
        private int thirdPlaceDropTypeId = 0;
        private int thirdPlaceDropRound = 0;
        private int losersDropTypeId = 0;
        private int losersDropRound = 0;
        private Set<Integer> preliminaryRounds = Set.of();
        private Set<Integer> seededKnockoutDrawRounds = Set.of();
        private Set<Integer> twoLegRounds = Set.of();
        private int[] tieredEuropeanEntries;

        private Builder(int typeId, Kind kind) { this.typeId = typeId; this.kind = kind; }

        public Builder matchdayToRoundDelta(int v) { this.matchdayToRoundDelta = v; return this; }
        public Builder encounters(Map<Integer, Integer> byTeamCount, int defaultEncounters) {
            this.encountersByTeamCount = byTeamCount; this.defaultEncounters = defaultEncounters; return this;
        }
        public Builder totalTeams(int v) { this.totalTeams = v; return this; }
        public Builder tieredEuropeanEntries(int directGroup, int firstRound, int secondRoundNew) {
            this.totalTeams = directGroup + firstRound + secondRoundNew;
            this.tieredEuropeanEntries = new int[]{directGroup, firstRound, secondRoundNew};
            return this;
        }
        public Builder groups(int count, int size) { this.groupCount = count; this.groupSize = size; return this; }
        public Builder qualifyPerGroupToKnockout(int v) { this.qualifyPerGroupToKnockout = v; return this; }
        public Builder playoffQualifyPerGroup(int v) { this.playoffQualifyPerGroup = v; return this; }
        public Builder qualifyTargetRound(int v) { this.qualifyTargetRound = v; return this; }
        public Builder groupRounds(int start, int end) { this.groupStartRound = start; this.groupEndRound = end; return this; }
        public Builder groupFixtureRoundOffset(int v) { this.groupFixtureRoundOffset = v; return this; }
        public Builder knockoutStartRound(int v) { this.knockoutStartRound = v; return this; }
        public Builder finalRound(int v) { this.finalRound = v; return this; }
        public Builder playoffRound(int v) { this.playoffRound = v; return this; }
        public Builder thirdPlaceDrop(int typeId, int round) { this.thirdPlaceDropTypeId = typeId; this.thirdPlaceDropRound = round; return this; }
        public Builder losersDrop(int typeId, int round) { this.losersDropTypeId = typeId; this.losersDropRound = round; return this; }
        public Builder preliminaryRounds(Set<Integer> v) { this.preliminaryRounds = v; return this; }
        public Builder seededKnockoutDrawRounds(Set<Integer> v) { this.seededKnockoutDrawRounds = v; return this; }
        public Builder twoLegRounds(Set<Integer> v) { this.twoLegRounds = v; return this; }

        public CompetitionFormat build() { return new CompetitionFormat(this); }
    }
}
