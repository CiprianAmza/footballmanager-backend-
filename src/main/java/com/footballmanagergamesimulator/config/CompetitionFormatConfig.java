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

        // --- League of Champions (4) ---
        // matchday-1 = round; 0-1 preliminary, 2-7 groups, 8 QF, 9 SF, 10 Final.
        // 4 groups of 4, top 2 → QF (round 8), 3rd → Stars Cup playoff (type 5, round 7).
        // Preliminary/qualifying losers drop to Stars Cup groups (type 5, round 1).
        // QF + SF are two-leg.
        byType.put(4, CompetitionFormat.builder(4, CompetitionFormat.Kind.GROUPS_THEN_KNOCKOUT)
                .matchdayToRoundDelta(-1)
                .totalTeams(40)
                .groups(4, 4)
                .qualifyPerGroupToKnockout(2)
                .qualifyTargetRound(8)
                .groupRounds(2, 7)
                .groupFixtureRoundOffset(1)
                .knockoutStartRound(8)
                .finalRound(10)
                .thirdPlaceDrop(5, 7)
                .losersDrop(5, 1)
                .preliminaryRounds(Set.of(0, 1))
                .seededKnockoutDrawRounds(Set.of(0, 1))
                .twoLegRounds(Set.of(8, 9))
                .build());

        // --- Stars Cup (5) ---
        // matchday = round; 1-6 groups, 7 playoff, 8 QF, 9 SF, 10 Final.
        // 4 groups of 4, winner → QF (round 8), runner-up → playoff (round 7).
        // The playoff draw (round 7) is seeded (LoC 3rd vs SC runners-up). Single-leg throughout.
        byType.put(5, CompetitionFormat.builder(5, CompetitionFormat.Kind.GROUPS_THEN_KNOCKOUT)
                .matchdayToRoundDelta(0)
                .groups(4, 4)
                .qualifyPerGroupToKnockout(1)
                .qualifyTargetRound(8)
                .groupRounds(1, 6)
                .groupFixtureRoundOffset(0)
                .knockoutStartRound(7)
                .finalRound(10)
                .playoffRound(7)
                .seededKnockoutDrawRounds(Set.of(7))
                .build());
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
