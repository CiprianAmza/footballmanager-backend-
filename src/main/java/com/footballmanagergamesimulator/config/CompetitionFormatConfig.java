package com.footballmanagergamesimulator.config;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Single registry of {@link CompetitionFormat} per competition type id
 * (1=League, 2=Cup, 3=Second League, 4=League of Champions, 5=Stars Cup).
 *
 * <p>The defaults below reproduce exactly the round numbers and group shape
 * that used to be hardcoded in {@code EuropeanCompetitionService},
 * {@code MatchdayCoordinator}, {@code FixtureSchedulingService}, and
 * {@code MatchEngineConfig.Knockout}. Production now reads from here so a format
 * change happens in one place.
 */
@Component
public class CompetitionFormatConfig {

    private final Map<Integer, CompetitionFormat> byType = new HashMap<>();

    /** Encounters by exact league size: 12 teams → 4 meetings, 14 → 3, everything else → 2. */
    private static final Map<Integer, Integer> LEAGUE_ENCOUNTERS = Map.of(12, 4, 14, 3);
    private static final int LEAGUE_DEFAULT_ENCOUNTERS = 2;

    public CompetitionFormatConfig() {
        // --- League (1) + Second League (3): round-robin, matchday == round.
        // Encounters scale with team count (config-driven, single source for game + tests). ---
        byType.put(1, CompetitionFormat.builder(1, CompetitionFormat.Kind.LEAGUE)
                .encounters(LEAGUE_ENCOUNTERS, LEAGUE_DEFAULT_ENCOUNTERS).build());
        byType.put(3, CompetitionFormat.builder(3, CompetitionFormat.Kind.LEAGUE)
                .encounters(LEAGUE_ENCOUNTERS, LEAGUE_DEFAULT_ENCOUNTERS).build());

        // --- Cup (2): single-elimination knockout, matchday == round ---
        byType.put(2, CompetitionFormat.builder(2, CompetitionFormat.Kind.KNOCKOUT).build());

        // --- League of Champions (4) — SHAPE ONLY ---
        // Round boundaries (preliminary/group/knockout/final, two-leg, seeded draw)
        // are DERIVED from this shape by EuropeanFormatPlan, so changing totalTeams/
        // groups/qualifyPerGroup adapts the whole format in one place. With 40 teams,
        // 4 groups of 4, top 2 → knockout: preliminaries 0-1, groups 2-7, 8 QF,
        // 9 SF, 10 Final; QF + SF two-leg; preliminary draws coefficient-seeded.
        // 3rd → Stars Cup playoff (type 5, round 7); preliminary/qualifying losers
        // drop to Stars Cup groups (type 5, round 1).
        byType.put(4, CompetitionFormat.builder(4, CompetitionFormat.Kind.GROUPS_THEN_KNOCKOUT)
                .matchdayToRoundDelta(-1)
                .totalTeams(40)
                .groups(4, 4)
                .qualifyPerGroupToKnockout(2)
                .groupFixtureRoundOffset(1)
                .thirdPlaceDrop(5, 7)
                .losersDrop(5, 1)
                .build());

        // --- Stars Cup (5) — round boundaries derived from the group shape ---
        // 4 groups of 4 → 1-6 groups, 7 playoff, 8 QF, 9 SF, 10 Final (identical to
        // the previous hardcoded values). Per-group routing: 1 winner → knockout,
        // 1 runner-up → playoff. See starsCupFormat for the derivation.
        byType.put(5, starsCupFormat(4, 4, 1, 1));
    }

    /**
     * Builds the Stars Cup format from its group shape so a shape change adapts
     * the round numbers in one place (analogous to LoC's {@code EuropeanFormatPlan},
     * but kept self-contained because SC is 1-based and its playoff injects
     * external teams — LoC 3rd place — which the plan model can't express).
     *
     * <p>Structure (1-based, {@code matchdayToRoundDelta = 0}): group winners go
     * straight to the knockout; runners-up meet the external playoff entrants in a
     * seeded playoff. The knockout therefore fields {@code 2 × groupCount} teams
     * (winners + playoff winners), which must be a power of two.
     * <pre>
     *   groups   : 1 .. G            where G = (groupSize - 1) * 2
     *   playoff  : G + 1             (seeded draw, single-leg)
     *   knockout : G + 2 .. G + 1 + K   where K = log2(2 * groupCount)
     * </pre>
     * With 4 groups of 4: G=6, K=3 → groups 1-6, playoff 7, QF/SF/Final 8-10.
     */
    private CompetitionFormat starsCupFormat(int groupCount, int groupSize,
                                             int directQualifyPerGroup, int playoffQualifyPerGroup) {
        int groupStart = 1;
        int groupEnd = groupStart + (groupSize - 1) * 2 - 1;
        int playoff = groupEnd + 1;
        // Knockout field = direct qualifiers + playoff winners. The playoff pairs each
        // group's playoff qualifiers with an equal number of external (LoC) drop-outs,
        // so it yields groupCount × playoffQualifyPerGroup winners.
        int knockoutEntrants = groupCount * (directQualifyPerGroup + playoffQualifyPerGroup);
        if (Integer.bitCount(knockoutEntrants) != 1) {
            throw new IllegalArgumentException("Stars Cup knockout field (groupCount × (direct+playoff) = "
                    + knockoutEntrants + ") must be a power of two");
        }
        int knockoutRounds = Integer.numberOfTrailingZeros(knockoutEntrants); // log2
        int finalRound = playoff + knockoutRounds;

        return CompetitionFormat.builder(5, CompetitionFormat.Kind.GROUPS_THEN_KNOCKOUT)
                .matchdayToRoundDelta(0)
                .groups(groupCount, groupSize)
                .qualifyPerGroupToKnockout(directQualifyPerGroup)
                .playoffQualifyPerGroup(playoffQualifyPerGroup)
                .qualifyTargetRound(playoff + 1)   // group winners enter the QF
                .groupRounds(groupStart, groupEnd)
                .groupFixtureRoundOffset(0)
                .knockoutStartRound(playoff)       // playoff is the first knockout-type round
                .finalRound(finalRound)
                .playoffRound(playoff)
                .seededKnockoutDrawRounds(Set.of(playoff))
                .build();
    }

    /** Format for a competition type, or a plain LEAGUE format as a safe fallback. */
    public CompetitionFormat get(int typeId) {
        return byType.computeIfAbsent(typeId,
                t -> CompetitionFormat.builder(t, CompetitionFormat.Kind.LEAGUE).build());
    }

    /** True if the given competition type plays the given round over two legs. */
    public boolean isTwoLeg(int typeId, long round) {
        CompetitionFormat f = byType.get(typeId);
        return f != null && f.isTwoLeg(round);
    }
}
